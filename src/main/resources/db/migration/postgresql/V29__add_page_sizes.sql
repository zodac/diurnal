-- Per-section "items per page" overrides (see User.pageSizes / PageSizePref): a jsonb array of
-- {section, pageSize} objects, holding an entry ONLY for the sections the user gave their own value.
--
-- Nullable, and an absent entry means the same thing as a NULL column: that section follows the
-- general users.page_size preference. That keeps "the default everywhere" to a single representation
-- and needs no backfill - every existing user starts with no overrides, which is exactly the
-- behaviour they have today.
ALTER TABLE users ADD COLUMN page_sizes jsonb;
