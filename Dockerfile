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

FROM eclipse-temurin:25-jre AS runtime

# ffmpeg/ffprobe for the worker role (§9.2). Debian base (not Alpine/musl) - the most common
# source of "works on my machine" FFmpeg bugs is a libc/codec mismatch, not worth risking here.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown appuser:appuser app.jar
USER appuser

EXPOSE 8080
# preferIPv4Stack: the default Docker bridge network gives containers no outbound IPv6 route.
# S3-compatible endpoints (Hetzner Object Storage, GHCR, etc.) are commonly dual-stack, and the
# JVM's resolver tends to prefer AAAA records - without this, every attempt to reach one fails
# immediately with "Network is unreachable" (found 2026-08-30: S3BucketInitializer's first bucket
# call). Caddy (Go) doesn't hit this because Go's resolver behaves differently, which is why only
# app, not caddy, showed the symptom.
ENTRYPOINT ["java", "-Djava.net.preferIPv4Stack=true", "-jar", "/app/app.jar"]
