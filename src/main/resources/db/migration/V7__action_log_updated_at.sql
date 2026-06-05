-- Track when a log entry was last modified (e.g. count incremented/decremented).
-- Mirrors the created_at/updated_at convention used by the users and actions tables.
ALTER TABLE action_logs
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Existing rows have never been modified since creation, so seed from created_at.
UPDATE action_logs SET updated_at = created_at;
