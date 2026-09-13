-- Title search (GET /api/search/videos). pg_trgm is a trusted extension since PG13,
-- so the database owner can create it without superuser rights.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- gin_trgm_ops serves both matching operators the search uses: ILIKE '%q%' and word similarity (<%).
CREATE INDEX idx_videos_title_trgm ON videos USING gin (title gin_trgm_ops);
