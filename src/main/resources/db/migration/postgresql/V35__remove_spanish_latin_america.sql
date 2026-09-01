-- The Spanish (Latin America) language option ('es-419') is removed - the offered Spanish catalogue is
-- condensed down to a single 'es-ES' entry (see net.zodac.diurnal.user.Language). Any account that had
-- explicitly chosen 'es-419' since it was offered is backfilled to 'es-ES', the same target V34 already
-- established as this project's one Spanish variant - not a guess, the same value BASE_LANGUAGE_FALLBACK
-- already resolved an unmatched Spanish region to.
UPDATE users SET language = 'es-ES' WHERE language = 'es-419';
