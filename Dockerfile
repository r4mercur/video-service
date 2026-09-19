# One image for both roles (CLAUDE.md §3.1) - SPRING_PROFILES_ACTIVE at runtime decides
# api, worker, or both. Nothing role-specific is baked in here.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Wrapper + build files first so dependency resolution is cached across source-only changes.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN ./gradlew --no-daemon dependencies || true

COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# The AOT cache below can only hold classes loaded from plain JAR files on the class path, not
# from the nested JARs inside a fat JAR - so unpack it into app.jar + lib/ (the manifest's
# Class-Path points at lib/). Copied to a fixed name first so the ENTRYPOINT doesn't depend on
# the project version.
RUN cp build/libs/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --destination application

FROM eclipse-temurin:25-jre AS runtime

# ffmpeg/ffprobe for the worker role (§9.2). Debian base (not Alpine/musl) - the most common
# source of "works on my machine" FFmpeg bugs is a libc/codec mismatch, not worth risking here.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
WORKDIR /app
# Root-owned, read-only for appuser: the process has no reason to modify its own JARs,
# and the AOT cache is rejected anyway if a JAR changes after training.
COPY --from=build /workspace/application ./application

# AOT cache training run (JEP 483/514/515): starts the Spring context once, records which
# classes get loaded and linked plus early method profiles, and writes them to app.aot. Cuts
# startup roughly in half (measured 7.7 s -> 4.1 s to a refreshed context).
#
# Deliberately NOT GraalVM native image or Spring AOT (spring.aot.enabled): both evaluate
# @Profile/@Conditional at build time, which breaks choosing the role at runtime (§3.1). The AOT
# cache holds class metadata only, no bean decisions - it stays valid for every profile
# combination; classes the training run didn't load simply load normally.
#
# Must run in THIS stage: the cache is only accepted by the exact JVM build that created it,
# with the same class path and unmodified JARs. If that ever doesn't hold, the JVM logs a
# warning and starts without the cache - slower, never broken.
#
# spring.context.exit=onRefresh stops after bean creation, before the web server, @Scheduled
# jobs and ApplicationRunners (TestUserSeeder) start. There is no Postgres or S3 during the
# image build, so the training run avoids both: the S3 client is lazy already
# (S3BucketInitializer), Flyway and schema validation are switched off, and Hibernate is told
# not to open a connection for JDBC metadata - which is why the dialect must be set explicitly
# here despite Hibernate's HHH90000025 warning (without it, startup fails). The datasource
# values are placeholders that are never connected to. Nothing from this run ends up in the
# cache except class metadata and profiles - no config, no keys (the ephemeral JWT key
# generated here is discarded).
#
# api,worker loads the superset of classes of both roles.
RUN java -XX:AOTCacheOutput=application/app.aot \
        -Djava.net.preferIPv4Stack=true \
        -Dspring.context.exit=onRefresh \
        -Dspring.profiles.active=api,worker \
        -Dspring.datasource.url=jdbc:postgresql://localhost:1/aot-training \
        -Dspring.datasource.username=aot-training \
        -Dspring.datasource.password=aot-training \
        -Dspring.flyway.enabled=false \
        -Dspring.jpa.hibernate.ddl-auto=none \
        -Dspring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect \
        -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
        -jar application/app.jar

USER appuser

EXPOSE 8080
# preferIPv4Stack: the default Docker bridge network gives containers no outbound IPv6 route.
# S3-compatible endpoints (Hetzner Object Storage, GHCR, etc.) are commonly dual-stack, and the
# JVM's resolver tends to prefer AAAA records - without this, every attempt to reach one fails
# immediately with "Network is unreachable" (found 2026-08-30: S3BucketInitializer's first bucket
# call). Caddy (Go) doesn't hit this because Go's resolver behaves differently, which is why only
# app, not caddy, showed the symptom.
ENTRYPOINT ["java", "-XX:AOTCache=/app/application/app.aot", "-Djava.net.preferIPv4Stack=true", "-jar", "/app/application/app.jar"]
