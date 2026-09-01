-- Corrects V33's backfill target: the project's chosen default Spanish variant is 'es-ES', not 'es-419' (see
-- net.zodac.diurnal.user.Language.BASE_LANGUAGE_FALLBACK). V33 is immutable once applied (see CLAUDE.md), so this
-- is expressed as a new migration rather than an edit to it.
UPDATE users SET language = 'es-ES' WHERE language = 'es-419';
