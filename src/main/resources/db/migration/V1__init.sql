CREATE EXTENSION IF NOT EXISTS citext;

-- =========================================================================
-- users
-- =========================================================================
CREATE TABLE users
(
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         CITEXT       NOT NULL UNIQUE,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER'
        CONSTRAINT users_role_check CHECK (role IN ('USER', 'ADMIN')),
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- =========================================================================
-- categories
-- =========================================================================
CREATE TABLE categories
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug       VARCHAR(50)  NOT NULL UNIQUE,
    name       VARCHAR(100) NOT NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    active     BOOLEAN      NOT NULL DEFAULT true
);

-- =========================================================================
-- videos
-- =========================================================================
CREATE TABLE videos
(
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES users (id),
    category_id      BIGINT       NOT NULL REFERENCES categories (id),
    title            VARCHAR(200) NOT NULL,
    description      TEXT,
    slug             VARCHAR(220) NOT NULL UNIQUE,
    status           VARCHAR(20)  NOT NULL DEFAULT 'UPLOADING'
        CONSTRAINT videos_status_check CHECK (status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED', 'BLOCKED')),
    visibility       VARCHAR(20)  NOT NULL
        CONSTRAINT videos_visibility_check CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    duration_seconds INT,
    width            INT,
    height           INT,
    size_bytes       BIGINT,
    storage_prefix   VARCHAR(255),
    playlist_key     VARCHAR(255),
    thumbnail_key    VARCHAR(255),
    published_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_videos_status_visibility_published_at ON videos (status, visibility, published_at DESC);
CREATE INDEX idx_videos_category_published_at ON videos (category_id, published_at DESC);
CREATE INDEX idx_videos_user_created_at ON videos (user_id, created_at DESC);

-- =========================================================================
-- video_renditions
-- =========================================================================
CREATE TABLE video_renditions
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    video_id     UUID         NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    height       INT          NOT NULL,
    bitrate_kbps INT          NOT NULL,
    playlist_key VARCHAR(255) NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    CONSTRAINT uq_video_renditions_video_height UNIQUE (video_id, height)
);

-- =========================================================================
-- upload_sessions
-- =========================================================================
CREATE TABLE upload_sessions
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    video_id      UUID         NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    s3_upload_id  VARCHAR(255) NOT NULL,
    s3_key        VARCHAR(255) NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    completed_at  TIMESTAMPTZ
);

-- =========================================================================
-- transcode_jobs
-- =========================================================================
CREATE TABLE transcode_jobs
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    video_id     UUID        NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CONSTRAINT transcode_jobs_status_check CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    attempts     INT         NOT NULL DEFAULT 0,
    max_attempts INT         NOT NULL DEFAULT 3,
    locked_at    TIMESTAMPTZ,
    locked_by    VARCHAR(255),
    last_error   TEXT,
    scheduled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_transcode_jobs_status_scheduled_at ON transcode_jobs (status, scheduled_at);

-- =========================================================================
-- reports
-- =========================================================================
CREATE TABLE reports
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    video_id         UUID        NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    reporter_user_id UUID        NOT NULL REFERENCES users (id),
    reason           VARCHAR(100) NOT NULL,
    detail           TEXT,
    status           VARCHAR(20) NOT NULL DEFAULT 'OPEN'
        CONSTRAINT reports_status_check CHECK (status IN ('OPEN', 'REVIEWED', 'DISMISSED')),
    handled_by       UUID REFERENCES users (id),
    handled_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- =========================================================================
-- refresh_tokens
-- =========================================================================
CREATE TABLE refresh_tokens
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id),
    token_hash  VARCHAR(255) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked_at  TIMESTAMPTZ,
    replaced_by BIGINT REFERENCES refresh_tokens (id),
    user_agent  VARCHAR(255)
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE UNIQUE INDEX idx_refresh_tokens_token_hash ON refresh_tokens (token_hash);

-- =========================================================================
-- video_view_stats
-- =========================================================================
CREATE TABLE video_view_stats
(
    video_id UUID   NOT NULL REFERENCES videos (id) ON DELETE CASCADE,
    day      DATE   NOT NULL,
    views    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (video_id, day)
);
