-- Raise the daily count ceiling from 255 to 999.
ALTER TABLE action_logs DROP CONSTRAINT action_logs_count_range;
ALTER TABLE action_logs ADD CONSTRAINT action_logs_count_range
    CHECK (count >= 1 AND count <= 999);
