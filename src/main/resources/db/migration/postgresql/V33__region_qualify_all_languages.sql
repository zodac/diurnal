-- Existing 'es' rows predate the Spain/Latin-America split, so there is no record of which the user actually
-- meant. Backfilled to 'es-419' rather than 'es-ES' for the same reason net.zodac.diurnal.user.Language's
-- BASE_LANGUAGE_FALLBACK prefers it: Latin America's combined Spanish-speaking population is far larger than
-- Spain's, so it is the statistically more likely intent with no other signal to go on.
UPDATE users SET language = 'es-419' WHERE language = 'es';
UPDATE users SET language = 'ar-SA' WHERE language = 'ar';
UPDATE users SET language = 'ja-JP' WHERE language = 'ja';
