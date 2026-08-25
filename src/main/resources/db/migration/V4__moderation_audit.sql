-- =========================================================================
-- audit_log (AP7) - justification requirement for moderation actions (CLAUDE.md 12).
-- video_id/report_id deliberately ON DELETE SET NULL instead of CASCADE: the audit entry
-- must be preserved even when the video or its associated report is deleted later
-- (deletion is only possible without an open report anyway).
-- =========================================================================
CREATE TABLE audit_log
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_user_id  UUID        NOT NULL REFERENCES users (id),
    action         VARCHAR(30) NOT NULL
        CONSTRAINT audit_log_action_check CHECK (action IN
            ('VIDEO_BLOCKED', 'VIDEO_UNBLOCKED', 'REPORT_DISMISSED', 'REPORT_UPHELD')),
    video_id       UUID REFERENCES videos (id) ON DELETE SET NULL,
    report_id      BIGINT REFERENCES reports (id) ON DELETE SET NULL,
    reason         TEXT        NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_log_video_id ON audit_log (video_id);
CREATE INDEX idx_audit_log_created_at ON audit_log (created_at DESC);

-- Cursor pagination for the admin report list (CLAUDE.md 3.2: no OFFSET paging).
CREATE INDEX idx_reports_status_created_at ON reports (status, created_at DESC);
