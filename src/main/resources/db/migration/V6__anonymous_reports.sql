-- =========================================================================
-- Allow anonymous video reports (no account required to file a report,
-- CLAUDE.md 12 - DSA notice-and-action must not be gated behind login).
-- reporter_user_id stays as an FK for logged-in reporters, just no longer
-- mandatory; NULL means the report was filed anonymously.
-- =========================================================================
ALTER TABLE reports
    ALTER COLUMN reporter_user_id DROP NOT NULL;
