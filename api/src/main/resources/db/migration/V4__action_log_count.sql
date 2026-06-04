ALTER TABLE action_logs
    ADD COLUMN count INTEGER NOT NULL DEFAULT 1 CONSTRAINT action_logs_count_positive CHECK (count > 0);
