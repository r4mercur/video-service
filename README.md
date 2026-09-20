# video-service

Backend for a self-hosted video platform: users upload videos, the service transcodes them into
an HLS ladder and serves them straight from S3-compatible object storage. Watching is public and
needs no account; uploading does.

The Angular client lives in a separate repository:
[video-service-frontend](https://github.com/r4mercur/video-service-frontend).

---

## What it does

**Accounts and sessions**

- Self-contained identity: registration, login, logout — no external identity provider.
- Argon2id password hashing, short-lived RSA-signed JWT access tokens, refresh tokens in an
  `HttpOnly` cookie with rotation on every use and family-wide revocation when a replaced token
  shows up again.
- Rate limits on login, registration and reporting; stricter buckets for anonymous callers.
- Optional profile photos: uploaded directly to the API, normalised to a square JPEG by FFmpeg
  with the input format whitelisted, and falling back to initials when none is set.

**Upload**

- Browsers upload directly to object storage via presigned S3 multipart URLs — video bytes never
  pass through the JVM. The service only issues part URLs, validates and completes the upload.
- Uploads are resumable per part; abandoned multipart sessions are aborted by a scheduled job.
- Size and duration are capped (3 GB / 2 h), and the source file is validated with `ffprobe`
  before anything is transcoded.

**Transcoding**

- A database-backed job queue (`SELECT … FOR UPDATE SKIP LOCKED`) drives FFmpeg as an external
  process, with attempt counters, exponential backoff, stale-lock recovery and a hard per-job
  timeout.
- Renditions are 360p/720p/1080p H.264 + AAC as fragmented-MP4 HLS with 4-second segments. The
  ladder never exceeds the source resolution, and an already-compatible source is remuxed instead
  of re-encoded.
- Thumbnails and a sprite sheet for scrubbing are generated alongside; owners can replace the
  thumbnail with their own image.
- The original upload is retained for 30 days so a broken transcode can be repeated, then cleaned
  up automatically.

**Catalog and search**

- Public feed with cursor pagination, category filter and sort options; channel pages per user; a
  private "my videos" view for owners.
- Typo-tolerant title search backed by a PostgreSQL trigram index, with numbered pages.
- A fixed, admin-maintained category taxonomy — exactly one category per video — including an
  age-restricted category that viewers have to opt into.

**Delivery**

- Public videos are fetched by the browser directly from the storage origin. Cache headers are
  written as object metadata at upload time, so no proxy sits in the media path.
- Private videos live under a separate prefix on a non-public bucket; playlists are rewritten at
  request time with presigned, short-lived segment URLs, so protection reaches down to individual
  segments.
- Visibility changes move thousands of objects between prefixes and therefore run as a background
  job: the request returns `202 Accepted`, playback keeps working from the old prefix, and the new
  visibility takes effect only once every object has moved.

**Moderation and administration**

- Reports can be filed by anyone, logged in or not (notice-and-action).
- Admins review reports, block and unblock videos, manage categories, remove a profile photo with
  a required reason and trigger a re-transcode; every action is written to an audit log.
- Deleting a video hides it immediately for everyone and empties its storage in a retryable
  background job, so a slow object store can never leave a half-deleted video behind.

**Observability**

- Actuator health and Prometheus metrics, Grafana dashboards and alert rules for the job queue,
  HTTP errors, refresh-token reuse and stuck deletions.
- Because media never touches the application, two independent signals cover delivery: a synthetic
  probe against the storage origin, and real-user playback telemetry reported by hls.js in the
  browser.

---

## Tech stack

| Layer          | Choice                                                                              |
| -------------- | ----------------------------------------------------------------------------------- |
| Runtime        | Java 25, Spring Boot 4.1 (Gradle)                                                   |
| Database       | PostgreSQL 17, Flyway migrations (`ddl-auto=validate`)                              |
| Object storage | S3-compatible via AWS SDK v2 — Garage locally, Hetzner Object Storage in production |
| Media          | FFmpeg / ffprobe as external processes                                              |
| Security       | Spring Security, OAuth2 resource server, Nimbus JWT, BouncyCastle (Argon2id), Bucket4j |
| API docs       | springdoc-openapi (`/swagger-ui.html`)                                              |
| Monitoring     | Actuator + Micrometer → Prometheus → Grafana                                        |
| Edge           | Caddy (TLS, API and SPA — not in the media path)                                    |

---

## Architecture at a glance

**One project, one image, two roles.** Spring profiles decide what an instance does:

- `api` — HTTP endpoints
- `worker` — job poller and FFmpeg

Both run in the same container by default (`SPRING_PROFILES_ACTIVE=api,worker`). Under load a
second container from the same image runs `worker` only, so a two-hour transcode can never block
request threads.

**Packages are cut by feature first, then by layer.** Each feature owns its `web/`, `service/`,
`repository/`, `entity/`, `dto/` and `storage/` sub-packages:

```
com.bjarne.videoservice
├── config/        Spring configuration and @ConfigurationProperties records
├── identity/      users, tokens, login and registration
├── catalog/       videos, categories, visibility, views, deletion
├── upload/        multipart sessions and presigned part URLs
├── transcoding/   job queue, FFmpeg, HLS packaging
├── delivery/      manifests, presigned playback, telemetry, origin probe
├── moderation/    reports, admin actions, audit log
└── shared/        cursor paging, error handling, rate limiting, cache policy
```

Everything that talks to the AWS SDK is confined to `storage/` sub-packages, and there is no
provider-specific branching anywhere in application code — swapping the object store is a matter
of configuration.

**Lifecycle of an upload**

```
POST /api/videos                → validate, create session, presigned part URLs
browser → object storage        → parts uploaded directly, retried individually
POST /api/videos/{id}/complete  → complete multipart, enqueue transcode job
worker                          → ffprobe, FFmpeg ladder, thumbnails, upload artifacts
                                → status READY, the video goes live
```

---

## Getting started

**Prerequisites**

- JDK 25
- Docker — Postgres, Garage, Caddy, Prometheus and Grafana all come from `compose.yaml`
- FFmpeg and ffprobe on `PATH` for the `worker` profile; the Docker image ships them, a local run
  does not

**Run the infrastructure and the app**

```bash
docker compose up -d
./gradlew bootRun --args="--spring.profiles.active=api,worker"
```

Garage is bootstrapped automatically: the `garage-init` one-shot container creates the layout, a
fixed development access key and the bucket, and is safe to run again on every `compose up`.

| Service                       | URL                                          |
| ----------------------------- | -------------------------------------------- |
| API                           | http://localhost:8080/api                    |
| Swagger UI                    | http://localhost:8080/swagger-ui.html        |
| Health                        | http://localhost:8080/api/actuator/health    |
| Media origin (Caddy → Garage) | http://localhost/public                      |
| Garage S3 API                 | http://localhost:3900                        |
| PostgreSQL                    | localhost:5432                               |
| Prometheus                    | http://localhost:9090                        |
| Grafana                       | http://localhost:3000                        |

The Angular dev server proxies `/api` to port 8080 and `/public` to port 80, so start this service
before the frontend.

**Tests**

```bash
./gradlew test       # full suite, including the FFmpeg-backed tests
./gradlew fastTest   # skips @Tag("ffmpeg") — the CI gate
```

Integration tests run against real Postgres and Garage containers via Testcontainers. No mocked S3
implementation is used anywhere, so the storage quirks that actually bite — multipart part sizes,
presigned URL edge cases, CORS — are exercised in CI rather than discovered in production.

---

## Configuration

Everything environment-specific is a property; dev, test and production run identical code:

| Property                             | Purpose                                                       |
| ------------------------------------ | ------------------------------------------------------------- |
| `app.storage.endpoint`/`region`/`bucket` | S3 endpoint and target bucket                              |
| `app.storage.path-style-access`      | `true` for Garage, `false` for a subdomain-addressed bucket   |
| `app.storage.public-base-url`        | origin browsers fetch public media from                       |
| `app.storage.cors-allowed-origins`   | bucket CORS — required, hls.js fetches segments via XHR       |
| `app.upload.*`                       | size cap, part size, URL and session lifetimes                |
| `app.transcode.*`                    | binary paths, ladder heights, timeouts, backoff, retention    |
| `app.delivery.*`                     | signed segment TTL, playlist cache, origin probe              |
| `app.auth.*`                         | token issuer and JWT key locations                            |
| `app.rate-limit.*`                   | per-endpoint buckets                                          |

`application.properties` carries working defaults for local development. For a server deployment,
copy `.env.example` to `.env` and fill it in — every variable there maps onto the same properties
through Spring's relaxed binding.

---

## API overview

All endpoints live under `/api`. Errors are RFC-9457 `ProblemDetail` responses produced by a single
global handler.

| Area       | Endpoints                                                                                       |
| ---------- | ----------------------------------------------------------------------------------------------- |
| Auth       | `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `GET /me`                 |
| Profile    | `PUT`/`DELETE /me/avatar`, `GET /users/{username}`                                              |
| Catalog    | `GET /categories`, `/videos`, `/videos/{slug}`, `/me/videos`, `/users/{username}/videos`, `/search/videos` |
| Upload     | `POST /videos`, `POST /videos/{id}/complete`, `GET /videos/{id}/status`                         |
| Management | `PATCH /videos/{id}`, `DELETE /videos/{id}`, `PUT`/`DELETE /videos/{id}/thumbnail`              |
| Playback   | `GET /videos/{id}/manifest`, `/videos/{id}/master.m3u8`, `/videos/{id}/{height}p/playlist.m3u8` |
| Engagement | `POST /videos/{id}/view`, `POST /videos/{id}/report`, `POST /playback/telemetry`                |
| Admin      | `/admin/categories`, `/admin/videos/{id}/block`, `/unblock`, `/retranscode`, `/admin/reports/**`, `/admin/users/{username}/avatar/remove` |

Two conventions worth knowing:

- Someone else's private video answers **404, not 403** — a 403 would confirm that it exists.
- Long-running changes answer **202 Accepted**: visibility changes and deletions enqueue a job
  instead of doing the work in a request thread. `GET /videos/{id}/status` reports progress.

The full, always-current contract is the OpenAPI document at `/v3/api-docs`, which the frontend
generates its TypeScript types from.

---

## Deployment

A single multi-stage `Dockerfile` produces one image for both roles. Releases are deliberately
manual: pushing a `v*.*.*` tag builds the image, publishes it to GHCR and deploys it over SSH to a
Hetzner Cloud server running `compose.prod.yaml` behind Caddy. Branch pushes only run the CI gate.

`infra/` holds the Terraform definition for server, firewall and bucket, the cloud-init template,
the restricted deploy script and a `pg_dump` + restic backup script. `infra/README.md` walks
through the one-time setup, the monitoring stack and the restore test.

---

## Further documentation

- `CLAUDE.md` — the design document: decisions, rejected alternatives and the reasoning behind
  them. Worth reading before changing anything structural.
- `infra/README.md` — provisioning, deployment, backups, monitoring.
- `HELP.md` — Spring and Gradle reference links from the project skeleton.
