# CLAUDE.md — video-service

Project context and working rules for this repository.

---

## 0. Working method (applies to every task)

1. **Always start with an implementation plan** before writing code.
2. **Ask explicitly when something is unclear** instead of silently making assumptions.
3. **Point out alternatives** when a solution isn't best practice — including the reasoning.
4. **Break work into realistic steps** so each step can be tackled in depth.
5. **Code and Comments should be in English**.

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
| Object storage — dev & test | Garage (S3-compatible), accessed via AWS SDK v2 |
| Object storage — production | Hetzner Object Storage (S3-compatible), accessed via AWS SDK v2 |
| Transcoding | FFmpeg as an external process |
| Frontend | Angular (current version, standalone components) |
| Reverse proxy | Caddy (automatic TLS) |
| Hosting | Hetzner Cloud |
| Monitoring | Actuator + Micrometer → Prometheus + Grafana |

### 2.1 Open source rule — and its one exception

**Every component is open source and self-hostable, with exactly one documented exception: the production object store.**

Rationale, so this doesn't get re-litigated later:

- Object storage is the one place where self-hosting costs the most. A Hetzner Cloud Volume runs at roughly **€53 per TB per month**; Hetzner Object Storage is around **€5 per TB per month**. At the 700 GB the storage estimate in §11 projects, that is the difference between the storage costing more than the server and costing less than a coffee.
- It is also the component with the **lowest lock-in in the entire stack**. The S3 API is a commodity. Migrating to Cloudflare R2, Backblaze B2 or a self-hosted Garage cluster is an `rclone sync` plus one changed endpoint in configuration.
- Hetzner Object Storage was chosen over Cloudflare R2 despite R2's zero-egress pricing, because this platform carries DSA and GDPR obligations (§12). A German provider means one data processing agreement and no third-country transfer assessment. With 20 TB of traffic included on the Cloud server and ~20 concurrent viewers, the egress volume that would make R2 pay off does not exist in this workload's profile.

Garage remains the dev and test implementation so the code path is identical in every environment (§9.1).

**No further exceptions without an equally explicit entry here.**

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
- ❌ **Never** branch on the storage provider in application code. There is exactly one S3 endpoint, supplied entirely by configuration. No `if (r2)`, no Hetzner-specific paths. This is what keeps the exception in §2.1 cheap to reverse.
- ❌ **Never** bulk-move storage objects inside a request thread → background job (§9.5).
- ✅ Visibility logic exists **in exactly one place** (`VisibilityPolicy`), not duplicated in every query.
- ✅ Errors as RFC-9457 `ProblemDetail` via a global `@RestControllerAdvice`.
- ✅ Every endpoint with write access checks ownership via `@PreAuthorize`.
- ✅ Cache headers are written **at upload time as object metadata**, never assumed to be added by a proxy downstream. Caddy is no longer in the media data path (§9.3).

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
    testImplementation 'org.testcontainers:testcontainers'  // Garage via GenericContainer
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

⚠️ **Pin all versions explicitly**, no `2.+` ranges. Check Springdoc for Boot 4 compatibility before adding it.

> **Why not `org.testcontainers:minio`?** Running MinIO in tests, Garage in dev and Ceph-based Hetzner Object Storage in production means three S3 implementations with three sets of quirks — and the quirks that bite (multipart part-size rules, presigned URL edge cases, CORS handling) are exactly the ones a test suite is supposed to catch. Standardize dev and test on Garage via `GenericContainer`. It costs a few lines of container setup and removes a whole class of "works locally, fails in CI, fails differently in production" bugs.

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
├── storage/         ObjectStore, CachePolicy, VisibilityMigrator, LifecycleConfig
├── moderation/      Report, ReportController, AdminController
└── shared/          ApiError, CursorPage, exceptions, ClockConfig
```

Spring Modulith to enforce the boundaries: introduce **only from AP5 onward** — earlier it just slows things down.

> `storage/` is new. It exists so that everything provider-adjacent — endpoint config, cache header policy, prefix moves, lifecycle rules — lives behind one boundary. That boundary is what makes §2.1's exception reversible in an afternoon.

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
                  visibility_target(PUBLIC|PRIVATE|NULL)   -- set while a move is in flight
                  duration_seconds width height size_bytes
                  storage_prefix playlist_key thumbnail_key has_custom_thumbnail
                  source_key source_deleted_at
                  published_at created_at updated_at

video_renditions  video_id height bitrate_kbps playlist_key size_bytes

upload_sessions   id video_id s3_upload_id s3_key expires_at completed_at

transcode_jobs    id video_id type(TRANSCODE|VISIBILITY_MIGRATION)
                  status attempts max_attempts
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

New columns and their reasons:

- `source_key` / `source_deleted_at` — the original upload is retained for 30 days, then removed by a lifecycle rule (§9.2). A re-transcode has to know whether the source still exists rather than probing the bucket and interpreting a 404.
- `visibility_target` — moving objects between prefixes takes minutes, not milliseconds (§9.5). `visibility` holds the *effective* value that delivery uses; `visibility_target` holds the requested one until the move completes.
- `transcode_jobs.type` — the visibility migration reuses the existing poller, `SKIP LOCKED` locking, retry and backoff machinery instead of duplicating it. One column is cheaper than a second queue.

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
| GET | `/api/videos/{id}/status` | JWT+Owner | Processing progress, incl. visibility migration |
| PATCH | `/api/videos/{id}` | JWT+Owner | Title, description, category, visibility |
| DELETE | `/api/videos/{id}` | JWT+Owner | Delete incl. object storage cleanup |
| PUT | `/api/videos/{id}/thumbnail` | JWT+Owner | Upload custom thumbnail (multipart, `file` field) |
| DELETE | `/api/videos/{id}/thumbnail` | JWT+Owner | Remove custom thumbnail, revert to auto-generated |
| GET | `/api/videos/{id}/manifest` | optional | Playlist URL (signed for `PRIVATE`) |
| POST | `/api/videos/{id}/report` | optional | Report; anonymous allowed (DSA notice-and-action), IP-rate-limited stricter than logged-in |
| POST | `/api/videos/{id}/view` | – | View counting, deduplicated |
| GET/POST | `/api/admin/**` | ADMIN | Categories, reports, bans |

> For someone else's `PRIVATE` video: **404, not 403** — 403 would confirm the video's existence.

> `PATCH /api/videos/{id}` returns **`202 Accepted`** when the payload changes `visibility`, and `200 OK` otherwise. A visibility change enqueues a migration job; it is not applied synchronously. See §9.5.

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

**Storage configuration is the only thing that differs between environments.** Dev, test and production run the same code against different values of:

```
storage.endpoint          # Garage locally, Hetzner Object Storage endpoint in production
storage.region
storage.bucket
storage.path-style-access # true for Garage; verify against Hetzner Object Storage
storage.public-base-url   # origin browsers fetch public media from (§9.3)
```

If anything beyond these properties needs to change to move between providers, that is a bug in `storage/`, not a configuration gap.

⚠️ **Multipart part sizes must be uniform except for the final part** — this holds across S3 implementations and is the most common cause of a `CompleteMultipartUpload` that succeeds locally against one backend and fails against another. Enforce it in `S3MultipartClient`, don't rely on the provider to be lenient.

### 9.2 Transcoding

1. `ffprobe` → **hard validation**: video track present, duration ≤ 2 h, resolution plausible
2. Ladder: **360p / 720p / 1080p**, H.264 High + AAC, HLS fMP4, 4 s segment length
3. Thumbnail + sprite sheet for scrubbing
4. Master playlist, upload the artifacts with their cache metadata (§9.3), status → `READY`
5. Source retention: the original stays under `source/{videoId}/` and is removed by a **bucket lifecycle rule after 30 days**. `source_deleted_at` is written when the rule fires or when the video is deleted, whichever comes first.

> **Why 30 days and not immediate deletion.** Transcode defects surface in the first weeks of production — broken ladders, wrong colour space on HDR sources, audio drift. Without the original, a re-encode is impossible and the upload is simply lost. Thirty days covers that window at negligible storage cost. Permanent retention roughly doubles the storage bill for a capability that goes unused after the pipeline stabilizes.

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
| `public/{videoId}/…` | Bucket is public-read. Segments and playlists are fetched **directly from the object storage endpoint** by the browser. Caddy is not in the media data path. | Set as object metadata at write time, see below |
| `private/{videoId}/…` | Bucket is not public. The backend generates the playlist at runtime with presigned segment URLs (TTL 3 h). Requests still go directly to the object storage endpoint. | `no-store` |
| `source/{videoId}/…` | Never public, never served. Lifecycle-deleted after 30 days (§9.2). | n/a |

#### Cache headers are object metadata, not proxy configuration

Because Caddy no longer sits in front of the media, `Cache-Control` must be supplied on every `PutObjectRequest`:

| Object | `Cache-Control` |
|---|---|
| Segments (`*.m4s`), init segments (`*.mp4`) | `public, max-age=31536000, immutable` |
| Playlists (`*.m3u8`) | `public, max-age=300` |
| `thumbnail.jpg`, `thumbnail_custom.jpg`, sprite sheets | `public, max-age=300` |

This moves work from AP6 into AP3/AP4: it is code in `HlsPackager` and in the thumbnail upload path, not a Caddyfile matcher. The distinction that §9.4 depends on — segments are immutable, thumbnails are not — is now enforced in `CachePolicy` inside `storage/`.

#### CORS is mandatory, not optional

`hls.js` fetches segments via XHR/`fetch`, not as a plain `<video src>`. That means CORS applies to every segment request — unlike Safari's native HLS, which will happily play the same URL without a preflight. This is a common way for playback to work on iOS and fail everywhere else.

Bucket CORS configuration:
- Allowed origins: the app origin (and the local dev origin)
- Allowed methods: `GET`, `HEAD`
- Allowed headers: must include `Range`
- Exposed headers: `Content-Length`, `Content-Range`, `Accept-Ranges`

The frontend's CSP needs `media-src` and `connect-src` entries for `storage.public-base-url`. That change belongs in the **frontend repository** (its AP9), not here — but it blocks playback if it is missed, so it is recorded here too.

#### Rejected alternatives

*Caddy as a reverse proxy in front of the bucket.* Would keep cache headers in one config file and keep everything on one origin, avoiding the CORS and CSP work above. Rejected because it puts all video egress through the server's uplink, making the server the bandwidth bottleneck for a workload whose entire cost profile is bandwidth. Revisit only if the CORS setup proves unexpectedly painful — it is a config change, not a rewrite.

*Caddy `forward_auth` against an internal Spring endpoint for private media.* Keeps playlists static, but requires segment requests to carry an auth token — awkward with `hls.js` and a memory-held token. **Not recommended.**

### 9.4 Custom thumbnails

The frame ffmpeg extracts during transcoding (9.2, `thumbnail.jpg`) is only the *default*. The
owner can replace it with their own image via `PUT /api/videos/{id}/thumbnail`.

- Stored under a separate key, `thumbnail_custom.jpg`, next to the auto-generated `thumbnail.jpg`
  — never overwrites it. `videos.thumbnail_key` points at whichever is active;
  `videos.has_custom_thumbnail` tracks which one that is.
- A later re-transcode (including admin `POST /api/admin/videos/{id}/retranscode`) regenerates
  `thumbnail.jpg` as usual but leaves an active custom thumbnail alone — it does not get
  overwritten.
- `DELETE /api/videos/{id}/thumbnail` deletes the custom object and reverts `thumbnail_key` back
  to the auto-generated one.
- Upload is a direct `multipart/form-data` request handled in the `api` process, not a presigned
  S3 upload like the video source (9.1): a thumbnail is a few MB, hard-capped — a fundamentally
  different risk profile than the GB-scale video bytes the "never MultipartFile" rule (3.2)
  targets. For the same reason, normalizing/validating the image (ffmpeg, single-frame resize to
  640px wide JPEG, mirroring the auto-thumbnail's own conventions) runs synchronously in the
  request instead of via the worker job queue — well under a second, unlike a multi-hour
  transcode.
- ⚠️ Because `thumbnail.jpg`/`thumbnail_custom.jpg` can change **in place** under the same key
  (unlike renditions), they must **not** get the year-long `immutable` cache from 9.3. A browser
  that already fetched the old image would otherwise never see a replacement. Since delivery no
  longer passes through Caddy, this is now enforced at write time: `CachePolicy` must return
  `max-age=300` for `.jpg` keys, and the thumbnail `PutObjectRequest` must never inherit the
  segment policy. **This is the single easiest place in the codebase to introduce a bug that is
  invisible for a year.** Cover it with a test that asserts the header on the request object.

### 9.5 Visibility changes

A visibility change moves every object of a video between the `public/` and `private/` prefixes. For a 2-hour video at 4-second segments across three renditions that is roughly **5,400 objects**, each requiring a server-side copy followed by a delete. Over a network connection to an external object store this takes minutes.

It therefore runs as a background job, never in a request thread:

1. `PATCH /api/videos/{id}` with a changed `visibility` writes `visibility_target`, enqueues a `VISIBILITY_MIGRATION` job and returns **`202 Accepted`**.
2. `visibility` — the value `VisibilityPolicy` and the manifest endpoint actually read — stays at its old value for the entire migration. Delivery continues to work correctly from the old prefix throughout.
3. The worker copies objects to the target prefix, verifies, then deletes the source objects. Per object, so an interrupted run resumes without losing segments.
4. Only after every object has moved does the job flip `visibility` to `visibility_target` and clear `visibility_target`.
5. `GET /api/videos/{id}/status` reports migration progress alongside transcode progress, so the frontend can show state rather than appearing to ignore the request.

**Idempotency is the hard requirement here.** The job must be safe to retry from any point. Copy-then-verify-then-delete, never delete-then-copy, and never assume a partially migrated prefix is empty.

> A `PRIVATE → PUBLIC` migration is also the moment a video first becomes publicly reachable. Step 4 is therefore the publication event — `published_at` is set there, not in the `PATCH` handler.

---

## 10. Work packages

| AP | Content | Effort | Status |
|---|---|---|---|
| **AP0** | Foundation: docker-compose (Postgres + Garage), config properties, profiles, `ProblemDetail` advice, Actuator secured | 0.5 d | ⬜ |
| **AP1** | Persistence: Flyway `V1__init.sql`, entities, `validate`, category seed, testcontainers base (Garage via `GenericContainer`) | 0.5 d | ⬜ |
| **AP2** | Auth: JwtService, SecurityFilterChain, register, login, refresh with rotation + reuse detection, logout, `/me` | 1.5 d | ⬜ |
| **AP3** | Upload: init, presigned multipart, complete, bucket CORS, `CachePolicy` + cache metadata on write, cleanup job, quotas | 2.5 d | ⬜ |
| **AP4** | Transcoding: JobPoller (`SKIP LOCKED`), ffprobe validation, FFmpeg ladder, thumbnails, retry/backoff, source lifecycle rule | 2–3 d | ⬜ |
| **AP5** | Catalog: public endpoints, cursor pagination, category filter, `VisibilityPolicy`, DTOs | 1 d | ⬜ |
| **AP6** | Delivery: public bucket policy, direct-from-bucket URLs, CORS verification against a real browser, range requests, signed playlists for `PRIVATE` | 1 d | ⬜ |
| **AP7** | Hardening: rate limits, validation, ownership, delete + storage cleanup, **visibility migration job (§9.5)**, view counting, reports, admin ban, audit log | 2.5 d | ⬜ |
| **AP9** | Observability: JSON logs with correlation ID, metrics, Prometheus/Grafana, queue alerting | 0.5 d | ⬜ |

*(AP8 has been replaced by the infrastructure packages below.)*

### Infrastructure packages

| AP | Content | Effort | Status |
|---|---|---|---|
| **I0** | Hetzner account, project, resource limit increase, SSH key, API token, domain + DNS zone | 0.5 d | ⬜ |
| **I1** | Server, firewall, object storage bucket — as a Terraform/`hcloud` script, not clicked together | 0.5 d | ⬜ |
| **I2** | OS hardening: unattended-upgrades, key-only SSH, fail2ban, Docker, disk layout | 0.5 d | ⬜ |
| **I3** | Storage Box, backup script (`pg_dump` + bucket sync), **restore tested for real once** | 0.5 d | ⬜ |
| **I4** | Caddy, DNS, TLS, smoke test | 0.25 d | ⬜ |
| **I5** | Deploy user, GitHub Actions pipeline | 0.5 d | ⬜ |

**Riskiest package: AP4.** FFmpeg failure modes are wide-ranging — broken containers, exotic codecs, HDR sources, 0-byte files. Plan for buffer time.

**Second riskiest: AP7's visibility migration.** Partial failures on a 5,000-object move are easy to write and hard to debug. Test it against a video with a realistic segment count, not a 10-second fixture.

---

## 11. Deployment (Hetzner)

The sizing scales with actual usage. There is no reason to pay for the target size before the platform has users.

| Phase | Trigger | Server | Media storage |
|---|---|---|---|
| **0** | Build-out, soft launch, no real users | CX33 (4 vCPU, 8 GB, 80 GB NVMe) | Hetzner Object Storage from day one |
| **1** | First real uploads, ~200 GB media | CX43 (8 vCPU, 16 GB, 160 GB) | same |
| **2** | ~500 videos, ~700 GB media | CX53 (16 vCPU, 32 GB, 320 GB) | same |

**Use Hetzner Object Storage from Phase 0**, even while a local disk would technically suffice. The point is that the storage code path is exercised in production configuration from the first deployment — CORS, presigned URLs, cache metadata and multipart behaviour all get validated before there is anything to lose.

> ⚠️ The previous recommendation in this document was CCX23/CCX33 (dedicated vCPU). **That is obsolete.** Hetzner raised prices on 15 June 2026 and the dedicated vCPU line roughly tripled — a CCX33 now costs around €165/month against €35 for a CX53. The reasoning behind the old rule (steal time makes transcode runtimes unpredictable) still holds, but transcoding here is an asynchronous queued job with no latency SLA: steal time makes a job slower, it does not block an HTTP request. If transcode throughput becomes a real complaint, the fix is a second `worker` container on a dedicated box (§3.1) — which is exactly what the profile split was built for — not a more expensive API server. Netcup's RS line is worth pricing at that point; it is currently several times cheaper per dedicated core.

**Other decisions:**

- **No Cloud Volume for media.** Media lives in object storage. A Volume is only needed if Postgres plus FFmpeg temp space outgrows the server's local NVMe, and 10 GB of temp per job (§9.2) fits comfortably from CX33 upward.
- ⚠️ **Hetzner Cloud backups and snapshots cover the boot disk only, not attached Volumes.** Not currently an issue since no Volume is planned — but it is the first thing to remember if one is ever added.
- **Backups:** `pg_dump` to a Storage Box via restic. Object storage is covered by bucket versioning plus, optionally, an `rclone` sync to Backblaze B2 as a second provider. A backup whose restore has never been executed is not a backup.
- **Deploy:** GitHub Actions → GHCR → `docker compose pull && up -d` over SSH as a restricted `deploy` user. Prefer a scoped SSH key over a Hetzner API token in GitHub secrets: a leaked API token can delete the server, a leaked deploy key cannot.
- **Caddy** still terminates TLS and serves the API and the Angular bundle. It is simply no longer in the media data path.
- **Location:** Falkenstein or Nuremberg. Put the bucket in the same location as the server, and verify whether traffic between them is internal and therefore free — that determines whether the rejected proxy alternative in §9.3 is worth revisiting.

**Storage estimate:** 1 h source ≈ 2–4 GB, renditions add ~1.5–2 GB.
500 videos at 20 min each ≈ **400–700 GB**, plus up to 30 days of retained sources on top (§9.2).

---

## 12. Legal framework (DE/EU)

A platform with user uploads is subject to DSA obligations (notice-and-action procedures, justification when blocking content), German Impressum requirements, and copyright issues. That's why `status=BLOCKED`, the report endpoint, and an audit log are part of the design **from the start** (AP7), not something retrofitted later.

The object storage decision in §2.1 adds obligations of its own:

- **Data processing agreement** with Hetzner covering both the Cloud server and Object Storage. User-uploaded video is personal data as soon as people are identifiable in it, which for a video platform is the default assumption.
- **Storage location** must be documented in the privacy policy. Keeping server and bucket in the same German location keeps this to one sentence — this is a substantial part of why Hetzner Object Storage was chosen over Cloudflare R2, which would require a third-country transfer assessment.
- **Deletion concept** must account for the lifecycle rule in §9.2. A deletion request has to remove the renditions, the thumbnails *and* any retained source object — the 30-day source retention is a processing purpose that needs to appear in the record of processing activities, not an implementation detail.

Actual legal drafting — terms of service, privacy policy, procedural deadlines — requires legal counsel. This plan does not replace it.

---

## 13. Cost model

Monthly, EUR, VAT included, Germany. Prices reflect the June 2026 Hetzner adjustment and should be re-verified before ordering.

| Item | Phase 0 | Phase 1 | Phase 2 |
|---|---|---|---|
| Server | 10.10 (CX33) | 19.03 (CX43) | 35.09 (CX53) |
| Primary IPv4 | ~0.65 | ~0.65 | ~0.65 |
| Backups (+20 %) | 2.02 | 3.81 | 7.02 |
| Object Storage | ~5 | ~5 | ~8 |
| Storage Box (backups) | ~4 | ~4 | ~4 |
| **Total** | **~22** | **~33** | **~55** |

Notes on the shape of this model:

- **Egress is not a line item.** The Cloud server includes 20 TB of traffic. At ~20 concurrent viewers of 1080p (~90 Mbit/s sustained) even continuous peak load stays inside that. This is the specific reason a hyperscaler was rejected: AWS charges $0.09/GB, Azure $0.087/GB and GCP $0.12/GB, which turns the same 3 TB into roughly $260/month on its own.
- **Storage, not compute, is what grows.** The lever that matters most is §9.2's source retention, followed by trimming the ladder — running only 360p and 720p until someone actually asks for 1080p roughly halves both storage and transcode CPU.
- **Supabase and equivalent BaaS platforms were evaluated and rejected.** They cannot run FFmpeg on a 2-hour video within their function execution limits, so the compute box would still be required, and their egress pricing sits at hyperscaler levels. The result is added cost with no infrastructure removed.