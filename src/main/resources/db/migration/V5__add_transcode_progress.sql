ALTER TABLE transcode_jobs
    ADD COLUMN progress_percent INT NOT NULL DEFAULT 0
        CONSTRAINT transcode_jobs_progress_percent_check CHECK (progress_percent BETWEEN 0 AND 100),
    ADD COLUMN current_step VARCHAR(100);
