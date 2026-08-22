# CLAUDE.md — video-service

Projektkontext und Arbeitsregeln für dieses Repository.

---

## 0. Arbeitsweise (gilt für jede Aufgabe)

1. **Immer erst ein Implementierungsplan**, bevor Code entsteht.
2. **Bei Unklarheiten explizit nachfragen** statt Annahmen still zu treffen.
3. **Alternativen aufzeigen**, wenn eine Lösung nicht Best Practice ist — inklusive Begründung.
4. **In realistische Arbeitsschritte teilen**, damit pro Schritt in die Tiefe gegangen werden kann.

---

## 1. Produkt

Video-Plattform mit nutzergenerierten Uploads.

| Regel | Ausprägung |
|---|---|
| Videos ansehen | Öffentlich, **ohne** Registrierung |
| Videos hochladen | Nur mit Account |
| Sichtbarkeit | `PUBLIC` oder `PRIVATE` — **kein** Unlisted, **kein** Draft |
| `PRIVATE` | Ausschließlich für den Besitzer sichtbar, keine Teilen-Funktion |
| Kategorien | Feste Taxonomie, admin-gepflegt. **Ein Video = genau eine Kategorie** |
| Freigabe | Direkt live nach Verarbeitung, keine Vorab-Moderation |
| Moderation | Nachträglich auf Meldung durch Nutzer |
| Max. Dateigröße | **3.000 MB** |
| Max. Dauer | **2 Stunden** |
| Erwartete Last | ~20 gleichzeitige Zuschauer |
| Livestreaming | Nicht vorgesehen |

---

## 2. Techstack

| Ebene | Technologie |
|---|---|
| Backend | Spring Boot 4.1.1, Java 25, Gradle |
| Datenbank | PostgreSQL 17 |
| Migrations | Flyway (`ddl-auto=validate`) |
| Object Storage | Garage oder MinIO (S3-kompatibel), Zugriff via AWS SDK v2 |
| Transcoding | FFmpeg als externer Prozess |
| Frontend | Angular (aktuelle Version, Standalone Components) |
| Reverse Proxy | Caddy (automatisches TLS) |
| Hosting | Hetzner Cloud |
| Monitoring | Actuator + Micrometer → Prometheus + Grafana |

**Alle Komponenten sind Open Source und selfhostbar.** Keine Managed Services.

---

## 3. Architekturprinzipien

### 3.1 Ein Service, zwei Rollen

Es gibt **genau ein Gradle-Projekt, ein JAR, ein Image**. Zwei Spring-Profile steuern die Rolle:

- `api` — HTTP-Endpunkte
- `worker` — Job-Poller und FFmpeg

**Start:** beide zusammen in einem Container (`SPRING_PROFILES_ACTIVE=api,worker`).
**Bei Lastproblemen:** zweiter Container aus demselben Image, nur mit `worker`. Kein zweites Projekt, kein zweites Repo.

> Grund: FFmpeg im API-Prozess ohne Trennung würde bei einem 2-Stunden-Transcode die Request-Threads blockieren.

### 3.2 Feste Regeln

- ❌ **Niemals** Video-Bytes durch Spring MVC (`MultipartFile`) leiten → presigned S3 Multipart.
- ❌ **Niemals** `spring.jpa.hibernate.ddl-auto=update` → Flyway, `validate`.
- ❌ **Niemals** JPA-Entities direkt als API-Response → DTOs.
- ❌ **Kein** `OFFSET`-Paging im Katalog → Cursor-Pagination.
- ✅ Sichtbarkeitslogik existiert **an genau einer Stelle** (`VisibilityPolicy`), nicht in jeder Query dupliziert.
- ✅ Fehler als RFC-9457 `ProblemDetail` über einen globalen `@RestControllerAdvice`.
- ✅ Jeder Endpunkt mit schreibendem Zugriff prüft Ownership per `@PreAuthorize`.

### 3.3 Boot-4-Stolpersteine

- Starter heißt `spring-boot-starter-webmvc` (nicht `-web`).
- Flyway wird **nicht** mehr autokonfiguriert, wenn nur das JAR da ist → `spring-boot-starter-flyway` nötig.
- **Jackson 3**: Imports lauten `tools.jackson.*`, nicht `com.fasterxml.jackson.*`.
- **JUnit 6**: alte Slice-Annotationen und Test-Utilities teilweise entfernt.
- Zu jedem Starter gibt es einen Test-Starter (`spring-boot-starter-webmvc-test` usw.).
- Boot-3- und Boot-4-Artefakte **nicht** mischen.

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
    implementation 'com.bucket4j:bucket4j-core'           // Rate Limiting
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

⚠️ **Alle Versionen explizit pinnen**, keine `2.+`-Ranges. Springdoc vor dem Einbau gegen Boot-4-Kompatibilität prüfen.

---

## 5. Package-Struktur

Fachliche Schnitte, **keine** `controller`/`service`/`repository`-Schichtpakete.

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

Spring Modulith zur Durchsetzung der Grenzen: **erst ab AP5** einführen, vorher bremst es.

---

## 6. Datenmodell

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

video_view_stats  video_id day views     -- PK (video_id, day), aggregiert
```

**Pflichtindizes ab V1:**

```sql
videos(status, visibility, published_at DESC)
videos(category_id, published_at DESC)
videos(user_id, created_at DESC)
transcode_jobs(status, scheduled_at)
refresh_tokens(user_id), refresh_tokens(token_hash)
```

**Kein** `email_verified_at` — keine E-Mail-Verifikation vorgesehen.
**Kein** `DRAFT` — `visibility` wird beim Upload-Init gesetzt und ist ab `READY` wirksam.

---

## 7. API-Oberfläche

| Methode | Pfad | Auth | Zweck |
|---|---|---|---|
| POST | `/api/auth/register` | – | Registrierung (E-Mail + Passwort) |
| POST | `/api/auth/login` | – | Access-Token + Refresh-Cookie |
| POST | `/api/auth/refresh` | Cookie | Rotation |
| POST | `/api/auth/logout` | Cookie | Widerruf |
| GET | `/api/me` | JWT | Eigenes Profil |
| GET | `/api/categories` | – | Kategorienliste |
| GET | `/api/videos` | optional | `?category=&sort=&cursor=&limit=` |
| GET | `/api/videos/{slug}` | optional | Detail, 404 bei fremdem `PRIVATE` |
| GET | `/api/me/videos` | JWT | Eigene Videos inkl. `PRIVATE` |
| GET | `/api/users/{username}/videos` | optional | Kanalseite, nur `PUBLIC` |
| POST | `/api/videos` | JWT | Upload initiieren → `videoId` + Part-URLs |
| POST | `/api/videos/{id}/complete` | JWT+Owner | Multipart abschließen → Job |
| GET | `/api/videos/{id}/status` | JWT+Owner | Verarbeitungsfortschritt |
| PATCH | `/api/videos/{id}` | JWT+Owner | Titel, Beschreibung, Kategorie, Sichtbarkeit |
| DELETE | `/api/videos/{id}` | JWT+Owner | Löschen inkl. S3-Cleanup |
| GET | `/api/videos/{id}/manifest` | optional | Playlist-URL (signiert bei `PRIVATE`) |
| POST | `/api/videos/{id}/report` | JWT | Meldung |
| POST | `/api/videos/{id}/view` | – | View-Zählung, dedupliziert |
| GET/POST | `/api/admin/**` | ADMIN | Kategorien, Reports, Sperren |

> Bei fremdem `PRIVATE`-Video **404 statt 403** — 403 bestätigt die Existenz.

---

## 8. Auth-Design

| Aspekt | Entscheidung |
|---|---|
| Identity | Vollständig im Backend, kein externer IdP |
| Passwort | Argon2id |
| Access-Token | JWT, 15 min, selbst signiert (RSA/EC via Nimbus), validiert über Resource-Server |
| Ablage Access-Token | Angular-Memory, **nicht** `localStorage` (XSS) |
| Refresh-Token | `HttpOnly` + `Secure` + `SameSite=Strict` Cookie, 30 Tage |
| Rotation | Bei jedem Refresh, alter Token wird ersetzt |
| Reuse-Detection | Bereits ersetzter Token vorgelegt → gesamte Token-Familie widerrufen |
| Session | Stateless, CSRF-Schutz nur auf dem Cookie-basierten Refresh-Pfad |

---

## 9. Medien-Pipeline

### 9.1 Upload

```
POST /api/videos          → Validierung, upload_session, CreateMultipartUpload
                          → presigned Part-URLs (Chunks 64–128 MB)
Browser → Storage         → Parts direkt hochladen, Retry pro Part
POST /{id}/complete       → CompleteMultipartUpload, Job einreihen
```

Verwaiste Sessions: `AbortMultipartUpload` nach 24 h per Scheduled Job.

### 9.2 Transcoding

1. `ffprobe` → **harte Validierung**: Videospur vorhanden, Dauer ≤ 2 h, Auflösung plausibel
2. Ladder: **360p / 720p / 1080p**, H.264 High + AAC, HLS fMP4, Segmentlänge 4 s
3. Thumbnail + Sprite-Sheet fürs Scrubbing
4. Master-Playlist, Upload der Artefakte, Status → `READY`

**CPU-Gegenmaßnahmen (wichtig bei 2 h Maximaldauer):**
- **Keine Renditions oberhalb der Quellauflösung.** 720p-Quelle → nur 360p + 720p.
- **Stream-Copy prüfen:** Ist die Quelle bereits H.264/AAC in passender Auflösung, wird nur remuxt statt neu encodiert. Spart bei vielen Uploads den Großteil der Rechenzeit.
- `-preset veryfast`, Single-Pass CRF.
- Harter Timeout pro Job mit `destroyForcibly()`.

**Disk-Bedarf pro Job:** ~3 GB Quelle + ~5–7 GB Renditions ≈ **10 GB Temp**. Worker-Concurrency startet bei **1**.

### 9.3 Ausspielung und private Videos

⚠️ **Zentraler Punkt:** Private Videos brauchen Schutz auf Segment-Ebene. Unguessable Keys reichen nicht.

**Lösung — getrennte Prefixes:**

| Prefix | Auslieferung | Caching |
|---|---|---|
| `public/{videoId}/…` | Caddy → Bucket, direkt | `immutable, max-age=31536000` auf Segmente |
| `private/{videoId}/…` | Bucket nicht öffentlich. Backend generiert Playlist zur Laufzeit mit presigned Segment-URLs (TTL 3 h) | `no-store` |

Beim Wechsel der Sichtbarkeit werden die Objekte zwischen den Prefixes verschoben.

*Alternative:* Caddy `forward_auth` gegen einen internen Spring-Endpunkt. Hält die Playlists statisch, erfordert aber, dass Segment-Requests ein Auth-Token mitführen — mit `hls.js` und Memory-Token unbequem. **Nicht empfohlen.**

---

## 10. Arbeitspakete

| AP | Inhalt | Aufwand | Status |
|---|---|---|---|
| **AP0** | Fundament: docker-compose (Postgres + Garage), Config-Properties, Profile, `ProblemDetail`-Advice, Actuator abgesichert | 0,5 d | ⬜ |
| **AP1** | Persistenz: Flyway `V1__init.sql`, Entities, `validate`, Kategorie-Seed, Testcontainers-Basis | 0,5 d | ⬜ |
| **AP2** | Auth: JwtService, SecurityFilterChain, Register, Login, Refresh mit Rotation + Reuse-Detection, Logout, `/me` | 1,5 d | ⬜ |
| **AP3** | Upload: Init, presigned Multipart, Complete, Bucket-CORS, Cleanup-Job, Quotas | 2 d | ⬜ |
| **AP4** | Transcoding: JobPoller (`SKIP LOCKED`), ffprobe-Validierung, FFmpeg-Ladder, Thumbnails, Retry/Backoff | 2–3 d | ⬜ |
| **AP5** | Katalog: öffentliche Endpunkte, Cursor-Pagination, Kategorie-Filter, `VisibilityPolicy`, DTOs | 1 d | ⬜ |
| **AP6** | Ausspielung: Caddy-Routing, Cache-Header, Range-Requests, signierte Playlists für `PRIVATE` | 1 d | ⬜ |
| **AP7** | Härtung: Rate-Limits, Validierung, Ownership, Löschen + S3-Cleanup, View-Counting, Reports, Admin-Sperre, Audit-Log | 1,5 d | ⬜ |
| **AP8** | Hetzner: Sizing, Volumes, Backups, Deploy-Pipeline, TLS | 1 d | ⬜ |
| **AP9** | Observability: JSON-Logs mit Correlation-ID, Metriken, Prometheus/Grafana, Queue-Alerting | 0,5 d | ⬜ |

**Riskantestes Paket: AP4.** FFmpeg-Fehlerbilder sind vielfältig — kaputte Container, exotische Codecs, HDR-Quellen, 0-Byte-Dateien. Puffer einplanen.

---

## 11. Deployment (Hetzner)

⚠️ **Nicht auf CX-Shared-vCPU transcodieren.** Steal Time macht Laufzeiten unvorhersehbar. Empfehlung: **CCX23/CCX33** (dedizierte vCPU) oder AX-Dedicated.

- Volume für Bucket-Daten und Temp-Space, **nicht** die Boot-Disk
- Backups: `pg_dump` nach S3 + Bucket-Versionierung
- Deploy: GitHub Actions → Registry → `docker compose pull && up -d`
- Caddy übernimmt TLS automatisch

**Speicherrechnung:** 1 h Quelle ≈ 2–4 GB, Renditions zusätzlich ~1,5–2 GB.
500 Videos à 20 min ≈ **400–700 GB**. Vor der Volume-Bestellung mit realistischen Zahlen nachrechnen.

---

## 12. Rechtlicher Rahmen (DE/EU)

Eine Plattform mit Nutzer-Uploads unterliegt DSA-Pflichten (Melde- und Abhilfeverfahren, Begründung bei Sperrung), Impressumspflicht und Urheberrechtsthemen. Deshalb sind `status=BLOCKED`, der Report-Endpunkt und ein Audit-Log **von Anfang an** Teil des Designs (AP7) und kein Nachrüstthema.

Für die konkrete rechtliche Ausgestaltung — Nutzungsbedingungen, Datenschutzerklärung, Verfahrensfristen — ist anwaltliche Beratung nötig. Dieser Plan ersetzt sie nicht.