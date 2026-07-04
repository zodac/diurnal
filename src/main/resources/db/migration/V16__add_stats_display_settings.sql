-- Whether the dashboard shows the per-action "stats summary" strip. Default on, matching
-- UserSettings.DEFAULT_SHOW_STATS_SUMMARY (the value the User entity assigns to new rows).
ALTER TABLE users ADD COLUMN show_stats_summary BOOLEAN NOT NULL DEFAULT true;
-- Number of decimal places used to render fractional stats (e.g. the weekly average). Default 1,
-- matching UserSettings.DEFAULT_DECIMAL_PLACES.
ALTER TABLE users ADD COLUMN decimal_places SMALLINT NOT NULL DEFAULT 1;
