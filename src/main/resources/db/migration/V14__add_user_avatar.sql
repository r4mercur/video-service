-- Profile photos (CLAUDE.md 9.8). NULL = no photo, the frontend falls back to the initials.
-- The key is new on every upload (public/avatars/{userId}/{uuid}.jpg), which is what lets the
-- object carry the immutable cache policy despite being user-replaceable.
ALTER TABLE users
    ADD COLUMN avatar_key TEXT;

-- An admin removing a profile photo is a moderation action like blocking a video (CLAUDE.md 12,
-- DSA justification requirement), but its target is a user, not a video or report.
-- ON DELETE SET NULL for the same reason as video_id/report_id in V4: the audit entry outlives
-- its target.
ALTER TABLE audit_log
    ADD COLUMN target_user_id UUID REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_audit_log_target_user_id ON audit_log (target_user_id);

ALTER TABLE audit_log DROP CONSTRAINT audit_log_action_check;

ALTER TABLE audit_log
    ADD CONSTRAINT audit_log_action_check CHECK (action IN
        ('VIDEO_BLOCKED', 'VIDEO_UNBLOCKED', 'REPORT_DISMISSED', 'REPORT_UPHELD', 'AVATAR_REMOVED'));
