# CLAUDE.md — video-service

Project context and working rules for this repository.

---

## 0. Working method (applies to every task)

1. **Always start with an implementation plan** before writing code.
2. **Ask explicitly when something is unclear** instead of silently making assumptions.
3. **Point out alternatives** when a solution isn't best practice — including the reasoning.
4. **Break work into realistic steps** so each step can be tackled in depth.

---

## 1. Product

Video platform with user-generated uploads.

| Rule | Behavior |
|---|---|
| Watching videos | Public, **without** registration |
| Uploading videos | Account required |
| Visibility | `PUBLIC` or `PRIVATE` — **no** Unlisted, **no** Draft |
| `PRIVATE` | Visible only to the owner, no sharing feature |
| Categories | Fixed taxonomy, admin-maintained. **One video = exactly one category** |
| Publishing | Goes live immediately after processing, no pre-moderation |
| Moderation | Retroactive, triggered by user reports |
| Max. file size | **3,000 MB** |
| Max. duration | **2 hours** |
| Expected load | ~20 concurrent viewers |
| Livestreaming | Not planned |

---

## 2. Tech stack

| Layer | Technology |
|---|---|
| Backend | Spring Boot 4.1.1, Java 25, Gradle |
| Database | PostgreSQL 17 |
| Migrations | Flyway (`ddl-auto=validate`) |
| Object storage | Garage or MinIO (S3-compatible), accessed via AWS SDK v2 |
| Transcoding | FFmpeg as an external process |
| Frontend | Angular (current version, standalone components) |
| Reverse proxy | Caddy (automatic TLS) |
| Hosting | Hetzner Cloud |
| Monitoring | Actuator + Micrometer → Prometheus + Grafana |

**All components are open source and self-hostable.** No managed services.

---

## 3. Architecture principles

### 3.1 One service, two roles

There is **exactly one Gradle project, one JAR, one image**. Two Spring profiles control the role:

- `api` — HTTP endpoints
- `worker` — job poller and FFmpeg

**Startup:** both together in one container (`SPRING_PROFILES_ACTIVE=api,worker`).
**Under load:** a second container from the same image, `worker` only. No second project, no second repo.

> Reason: running FFmpeg inside the API process without separation would block request threads during a 2-hour transcode.

### 3.2 Fixed rules

- ❌ **Never** stream video bytes through Spring MVC (`MultipartFile`) → presigned S3 multipart.
- ❌ **Never** `spring.jpa.hibernate.ddl-auto=update` → Flyway, `validate`.
- ❌ **Never** expose JPA entities directly as API responses → DTOs.
- ❌ **No** `OFFSET` paging in the catalog → cursor pagination.
- ✅ Visibility logic exists **in exactly one place** (`VisibilityPolicy`), not duplicated in every query.
- ✅ Errors as RFC-9457 `ProblemDetail` via a global `@RestControllerAdvice`.
- ✅ Every endpoint with write access checks ownership via `@PreAuthorize`.

### 3.3 Boot 4 pitfalls

- The starter is called `spring-boot-starter-webmvc` (not `-web`).
- Flyway is **no longer** autoconfigured when only the JAR is present → `spring-boot-starter-flyway` is required.
- **Jackson 3**: imports are `tools.jackson.*`, not `com.fasterxml.jackson.*`.
- **JUnit 6**: some old slice annotations and test utilities have been removed.
- Every starter has a matching test starter (`spring-boot-starter-webmvc-test`, etc.).
- Don't mix Boot 3 and Boot 4 artifacts.

---

## 4. Dependencies

```gradle
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-webmvc'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-security-oauth2-resource-server'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-flyway'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    implementation 'software.amazon.awssdk:s3'
    implementation 'org.bouncycastle:bcprov-jdk18on'      // Argon2id
    implementation 'com.bucket4j:bucket4j-core'           // Rate limiting
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui'

    developmentOnly 'org.springframework.boot:spring-boot-devtools'
    developmentOnly 'org.springframework.boot:spring-boot-docker-compose'

    runtimeOnly 'io.micrometer:micrometer-registry-prometheus'
    runtimeOnly 'org.postgresql:postgresql'

    testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-security-test'
    testImplementation 'org.springframework.boot:spring-boot-starter-data-jpa-test'
    testImplementation 'org.springframework.boot:spring-boot-testcontainers'
    testImplementation 'org.testcontainers:postgresql'
    testImplementation 'org.testcontainers:minio'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

⚠️ **Pin all versions explicitly**, no `2.+` ranges. Check Springdoc for Boot 4 compatibility before adding it.

---

## 5. Package structure

Feature-based cuts, **not** `controller`/`service`/`repository` layer packages.

```
com.bjarne.videoservice
├── config/          SecurityConfig, S3Config, JacksonConfig, WorkerConfig
├── identity/        User, RefreshToken, AuthController, AuthService, JwtService
├── catalog/         Video, Category, VideoRepository, CatalogController, VisibilityPolicy
├── upload/          UploadController, UploadService, S3MultipartClient, UploadSession
├── transcoding/     TranscodeJob, JobPoller, FfmpegRunner, HlsPackager, MediaProbe
├── moderation/      Report, ReportController, AdminController
└── shared/          ApiError, CursorPage, exceptions, ClockConfig
```

Spring Modulith to enforce the boundaries: introduce **only from AP5 onward** — earlier it just slows things down.

---

## 6. Data model

```
users             id(uuid) email(citext unique) username(unique) password_hash
                  role(USER|ADMIN) status(ACTIVE|SUSPENDED) created_at

refresh_tokens    id user_id token_hash expires_at revoked_at replaced_by user_agent

categories        id slug(unique) name sort_order active

videos            id(uuid) user_id category_id(NOT NULL) title description slug(unique)
                  status(UPLOADING|PROCESSING|READY|FAILED|BLOCKED)
                  visibility(PUBLIC|PRIVATE)
                  duration_seconds width height size_bytes
                  storage_prefix playlist_key thumbnail_key
                  published_at created_at updated_at

video_renditions  video_id height bitrate_kbps playlist_key size_bytes

upload_sessions   id video_id s3_upload_id s3_key expires_at completed_at

transcode_jobs    id video_id status attempts max_attempts
                  locked_at locked_by last_error scheduled_at created_at

reports           id video_id reporter_user_id reason detail
                  status(OPEN|REVIEWED|DISMISSED) handled_by handled_at created_at

video_view_stats  video_id day views     -- PK (video_id, day), aggregated
```

**Required indexes from V1:**

```sql
videos(status, visibility, published_at DESC)
videos(category_id, published_at DESC)
videos(user_id, created_at DESC)
transcode_jobs(status, scheduled_at)
refresh_tokens(user_id), refresh_tokens(token_hash)
```

**No** `email_verified_at` — no email verification planned.
**No** `DRAFT` — `visibility` is set at upload init and takes effect from `READY` onward.

---

## 7. API surface

| Method | Path | Auth | Purpose |
|---|---|---|---|
| POST | `/api/auth/register` | – | Registration (email + password) |
| POST | `/api/auth/login` | – | Access token + refresh cookie |
| POST | `/api/auth/refresh` | Cookie | Rotation |
| POST | `/api/auth/logout` | Cookie | Revocation |
| GET | `/api/me` | JWT | Own profile |
| GET | `/api/categories` | – | Category list |
| GET | `/api/videos` | optional | `?category=&sort=&cursor=&limit=` |
| GET | `/api/videos/{slug}` | optional | Detail, 404 for someone else's `PRIVATE` |
| GET | `/api/me/videos` | JWT | Own videos incl. `PRIVATE` |
| GET | `/api/users/{username}/videos` | optional | Channel page, `PUBLIC` only |
| POST | `/api/videos` | JWT | Initiate upload → `videoId` + part URLs |
| POST | `/api/videos/{id}/complete` | JWT+Owner | Complete multipart → job |
| GET | `/api/videos/{id}/status` | JWT+Owner | Processing progress |
| PATCH | `/api/videos/{id}` | JWT+Owner | Title, description, category, visibility |
| DELETE | `/api/videos/{id}` | JWT+Owner | Delete incl. S3 cleanup |
| GET | `/api/videos/{id}/manifest` | optional | Playlist URL (signed for `PRIVATE`) |
| POST | `/api/videos/{id}/report` | JWT | Report |
| POST | `/api/videos/{id}/view` | – | View counting, deduplicated |
| GET/POST | `/api/admin/**` | ADMIN | Categories, reports, bans |

> For someone else's `PRIVATE` video: **404, not 403** — 403 would confirm the video's existence.

---

## 8. Auth design

| Aspect | Decision |
|---|---|
| Identity | Fully in-house in the backend, no external IdP |
| Password | Argon2id |
| Access token | JWT, 15 min, self-signed (RSA/EC via Nimbus), validated via resource server |
| Access token storage | Angular memory, **not** `localStorage` (XSS) |
| Refresh token | `HttpOnly` + `Secure` + `SameSite=Strict` cookie, 30 days |
| Rotation | On every refresh, old token is replaced |
| Reuse detection | An already-replaced token is presented → the entire token family is revoked |
| Session | Stateless, CSRF protection only on the cookie-based refresh path |

---

## 9. Media pipeline

### 9.1 Upload

```
POST /api/videos          → validation, upload_session, CreateMultipartUpload
                          → presigned part URLs (64–128 MB chunks)
Browser → Storage         → upload parts directly, retry per part
POST /{id}/complete       → CompleteMultipartUpload, enqueue job
```

Orphaned sessions: `AbortMultipartUpload` after 24 h via a scheduled job.

### 9.2 Transcoding

1. `ffprobe` → **hard validation**: video track present, duration ≤ 2 h, resolution plausible
2. Ladder: **360p / 720p / 1080p**, H.264 High + AAC, HLS fMP4, 4 s segment length
3. Thumbnail + sprite sheet for scrubbing
4. Master playlist, upload the artifacts, status → `READY`

**CPU countermeasures (important given the 2 h max duration):**
- **No renditions above the source resolution.** 720p source → only 360p + 720p.
- **Check for stream copy:** if the source is already H.264/AAC at a suitable resolution, it's only remuxed instead of re-encoded. Saves most of the compute time across many uploads.
- `-preset veryfast`, single-pass CRF.
- Hard timeout per job with `destroyForcibly()`.

**Disk requirement per job:** ~3 GB source + ~5–7 GB renditions ≈ **10 GB temp**. Worker concurrency starts at **1**.

### 9.3 Delivery and private videos

⚠️ **Key point:** private videos need protection at the segment level. Unguessable keys aren't enough.

**Solution — separate prefixes:**

| Prefix | Delivery | Caching |
|---|---|---|
| `public/{videoId}/…` | Caddy → bucket, direct | `immutable, max-age=31536000` on segments |
| `private/{videoId}/…` | Bucket not public. Backend generates the playlist at runtime with presigned segment URLs (TTL 3 h) | `no-store` |

When visibility changes, the objects are moved between prefixes.

*Alternative:* Caddy `forward_auth` against an internal Spring endpoint. Keeps playlists static, but requires segment requests to carry an auth token — awkward with `hls.js` and a memory-held token. **Not recommended.**

---

## 10. Work packages

| AP | Content | Effort | Status |
|---|---|---|---|
| **AP0** | Foundation: docker-compose (Postgres + Garage), config properties, profiles, `ProblemDetail` advice, Actuator secured | 0.5 d | ⬜ |
| **AP1** | Persistence: Flyway `V1__init.sql`, entities, `validate`, category seed, testcontainers base | 0.5 d | ⬜ |
| **AP2** | Auth: JwtService, SecurityFilterChain, register, login, refresh with rotation + reuse detection, logout, `/me` | 1.5 d | ⬜ |
| **AP3** | Upload: init, presigned multipart, complete, bucket CORS, cleanup job, quotas | 2 d | ⬜ |
| **AP4** | Transcoding: JobPoller (`SKIP LOCKED`), ffprobe validation, FFmpeg ladder, thumbnails, retry/backoff | 2–3 d | ⬜ |
| **AP5** | Catalog: public endpoints, cursor pagination, category filter, `VisibilityPolicy`, DTOs | 1 d | ⬜ |
| **AP6** | Delivery: Caddy routing, cache headers, range requests, signed playlists for `PRIVATE` | 1 d | ⬜ |
| **AP7** | Hardening: rate limits, validation, ownership, delete + S3 cleanup, view counting, reports, admin ban, audit log | 1.5 d | ⬜ |
| **AP8** | Hetzner: sizing, volumes, backups, deploy pipeline, TLS | 1 d | ⬜ |
| **AP9** | Observability: JSON logs with correlation ID, metrics, Prometheus/Grafana, queue alerting | 0.5 d | ⬜ |

**Riskiest package: AP4.** FFmpeg failure modes are wide-ranging — broken containers, exotic codecs, HDR sources, 0-byte files. Plan for buffer time.

---

## 11. Deployment (Hetzner)

⚠️ **Don't transcode on CX shared vCPU.** Steal time makes runtimes unpredictable. Recommendation: **CCX23/CCX33** (dedicated vCPU) or AX dedicated.

- Volume for bucket data and temp space, **not** the boot disk
- Backups: `pg_dump` to S3 + bucket versioning
- Deploy: GitHub Actions → registry → `docker compose pull && up -d`
- Caddy handles TLS automatically

**Storage estimate:** 1 h source ≈ 2–4 GB, renditions add ~1.5–2 GB.
500 videos at 20 min each ≈ **400–700 GB**. Recalculate with realistic numbers before ordering the volume.

---

## 12. Legal framework (DE/EU)

A platform with user uploads is subject to DSA obligations (notice-and-action procedures, justification when blocking content), German Impressum requirements, and copyright issues. That's why `status=BLOCKED`, the report endpoint, and an audit log are part of the design **from the start** (AP7), not something retrofitted later.

Actual legal drafting — terms of service, privacy policy, procedural deadlines — requires legal counsel. This plan does not replace it.
