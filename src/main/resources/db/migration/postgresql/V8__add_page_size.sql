-- Per-user page size for the paginated action, stats and day-panel lists.
-- Mirrors the dark_mode preference: stored as a column on the users table.
ALTER TABLE users ADD COLUMN page_size INTEGER NOT NULL DEFAULT 10;
