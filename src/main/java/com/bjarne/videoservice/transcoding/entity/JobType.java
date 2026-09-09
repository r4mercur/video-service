package com.bjarne.videoservice.transcoding.entity;

public enum JobType {
    TRANSCODE,
    VISIBILITY_MIGRATION,
    /**
     * Rewrites Content-Type and Cache-Control on a video's existing storage objects. Needed once,
     * for videos uploaded before CachePolicy existed - their objects carry no Cache-Control at
     * all. Queued rather than run inline because it is one storage round-trip per object, which
     * for a full-length video is thousands of them (CLAUDE.md 9.5 makes the same argument for
     * VISIBILITY_MIGRATION).
     *
     * <p>The column is a plain VARCHAR(30) with no CHECK constraint (V9), so adding this value
     * needs no migration.
     */
    CACHE_METADATA_BACKFILL
}
