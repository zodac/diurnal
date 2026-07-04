-- Convert the "Action stats" preference (users.stats_fields) from TEXT to a jsonb array of
-- {key, enabled} objects (see User.statsFields / StatFieldPref), keeping every stat in the user's
-- arranged order so a field's position is stable whether shown or hidden.
--
-- Values written under the previous TEXT format (a comma-separated, "!"-prefixed key list) are NOT
-- valid JSON, and this is a display-only preference, so any existing value is reset to NULL — which
-- re-defaults to "show every stat" — rather than attempting a lossy in-SQL parse of the old format.
ALTER TABLE users ALTER COLUMN stats_fields TYPE jsonb USING NULL::jsonb;
