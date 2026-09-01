-- Tighten the count column: SMALLINT (max 32767) and a [1, 255] range constraint.
ALTER TABLE action_logs ALTER COLUMN count TYPE SMALLINT;

ALTER TABLE action_logs DROP CONSTRAINT action_logs_count_positive;
ALTER TABLE action_logs ADD CONSTRAINT action_logs_count_range
    CHECK (count >= 1 AND count <= 255);
