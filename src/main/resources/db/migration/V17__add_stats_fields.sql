-- User-configurable "Action stats" display preference: an ordered, comma-separated list of
-- ActionStatField keys selecting which per-action stats show on the Stats page (and in what order).
-- Each field's key appears in the user's arranged order, "!"-prefixed when disabled, so a stat's
-- position is stable whether it is shown or hidden. Nullable: NULL means "never customised" —
-- ActionStatField.displayFields(null) renders every stat in the default order, so newly-added stats
-- automatically appear for users who have not touched the setting.
-- This is a display preference only; StatsService always computes the full set of statistics.
ALTER TABLE users ADD COLUMN stats_fields TEXT;
