-- Asynchronous video deletion (CLAUDE.md 9.7): DELETE marks the video DELETING and a
-- VIDEO_DELETION job removes storage and the row. transcode_jobs.type is a plain VARCHAR(30)
-- without a CHECK constraint (V9), so only the video status needs a migration.
ALTER TABLE videos DROP CONSTRAINT videos_status_check;

ALTER TABLE videos
    ADD CONSTRAINT videos_status_check
        CHECK (status IN ('UPLOADING', 'PROCESSING', 'READY', 'FAILED', 'BLOCKED', 'DELETING'));
