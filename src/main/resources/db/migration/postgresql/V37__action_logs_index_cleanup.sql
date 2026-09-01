-- Removes a redundant index on action_logs and lets the remaining one answer the Stats page without touching the heap.
--
-- V3 created `CONSTRAINT action_logs_unique UNIQUE (user_id, action_id, log_date)`, whose backing index is on exactly those three columns in exactly
-- that order. V19 then added `idx_action_logs_user_action_date` over the same three columns in the same order: a duplicate. Its reasoning was sound
-- as far as it went (idx_action_logs_user_date cannot serve an action_id predicate) but it overlooked that the unique constraint already provided
-- the index it was reaching for - the very point V26 makes for `notes`, where no second index was created for that reason. Dropping it changes no
-- query plan; action_logs_unique serves every lookup it was serving.
--
-- The second change makes the Stats page's rollups (ActionLogQueries.MONTHLY_TOTALS_JPQL / DAILY_TOTALS_JPQL / ALL_DAILY_TOTALS_JPQL) index-only.
-- They filter on (user_id, action_id) and then read `count`, which is not in the index, so every matching row cost a heap fetch. Carrying `count`
-- as an INCLUDE payload removes that: the aggregate is answered from the index alone. The payload is free in storage terms - `count` is a SMALLINT
-- and fits in the index tuple's existing alignment padding, so the rebuilt index measures the same as the old one.
--
-- INCLUDE does not participate in the uniqueness: the constraint still enforces one row per (user, action, day), and `ON CONFLICT ON CONSTRAINT
-- action_logs_unique` in the increment/set upserts still resolves against it.
--
-- An index-only scan additionally needs the visibility map to be current, which is autovacuum's job; after a burst of writes the planner falls back
-- to a heap scan until the next vacuum. That is a graceful degradation to today's behaviour, not a regression.
--
-- Dropping and re-adding the constraint rebuilds its index under an ACCESS EXCLUSIVE lock. The table holds one row per (user, action, day), so this
-- is a sub-second operation at any realistic size, and it runs at startup before the application serves traffic.

DROP INDEX IF EXISTS idx_action_logs_user_action_date;

ALTER TABLE action_logs DROP CONSTRAINT action_logs_unique;
ALTER TABLE action_logs ADD CONSTRAINT action_logs_unique UNIQUE (user_id, action_id, log_date) INCLUDE (count);
