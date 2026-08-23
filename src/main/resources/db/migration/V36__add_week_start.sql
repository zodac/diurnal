-- The day the dashboard calendar's week starts on ('monday' ... 'sunday', see user/WeekStart). NULL means "follow the
-- account's language", resolved from CLDR (Monday for en-GB/es-ES, Sunday for en-US/ar-SA/ja-JP) - the same
-- NULL-means-the-derived-default shape `timezone` uses, so the automatic state has exactly one representation.
--
-- Deliberately NOT backfilled with the 'sunday' every calendar was fixed at before this setting existed: an existing
-- en-GB/es-ES account gets the locale-correct Monday-first grid on upgrade, which is the point of the default.
ALTER TABLE users ADD COLUMN week_start VARCHAR(9);
