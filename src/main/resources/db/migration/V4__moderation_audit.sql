-- =========================================================================
-- audit_log (AP7) - Begruendungspflicht bei Moderationsmassnahmen (CLAUDE.md 12).
-- video_id/report_id bewusst ON DELETE SET NULL statt CASCADE: der Audit-Eintrag
-- muss auch dann erhalten bleiben, wenn das Video oder der zugehoerige Report
-- spaeter geloescht wird (Loeschen ist ohnehin nur ohne offene Meldung moeglich).
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

-- Cursor-Pagination fuer die Admin-Report-Liste (CLAUDE.md 3.2: kein OFFSET-Paging).
CREATE INDEX idx_reports_status_created_at ON reports (status, created_at DESC);
