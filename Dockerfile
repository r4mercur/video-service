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
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
