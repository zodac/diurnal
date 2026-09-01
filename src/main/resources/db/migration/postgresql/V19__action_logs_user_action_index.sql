-- Supports the dashboard "most-recent actions this month" path (StatsService.forMostRecent):
--   1. selecting the active actions logged within the current month, grouped by action_id, and
--   2. loading the full log history for those few winning actions (action_id IN (...)).
-- The existing idx_action_logs_user_date (user_id, log_date) cannot serve an action_id predicate,
-- so per-action lookups previously fell back to a broader scan.
CREATE INDEX idx_action_logs_user_action_date ON action_logs (user_id, action_id, log_date);
