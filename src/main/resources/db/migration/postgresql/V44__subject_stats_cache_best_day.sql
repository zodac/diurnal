-- Adds the busiest-single-day figure (the "Most in a single day" stat) to the per-(user, subject) statistics cache.
--
-- WHAT IT REPLACES. Nothing: this is a new figure, not a corrected one. StatsService.assemble already reads the per-day rollup for the streak, gap
-- and days-with-multiples figures, so the maximum over those same rows is computed from a read that was happening anyway - there is no new query
-- and no new aggregate. Storing it here is what keeps the cached path serving the same figure set as the computed one, which is the invariant
-- SubjectStatsCachingTest's round-trip pins.
--
-- Measured cost of the two columns: 12 bytes/row (a DATE plus a BIGINT), taking a cached row from V43's 296 bytes to 308 - about 4%, or ~0.35 MB on
-- the 1,000-account, 30-subject deployment V43 sized itself against. Nothing about the read changes: it is still one primary-key lookup per user,
-- the 0.10-0.17 ms V43 measured.
--
-- WHY THE EXISTING ROWS ARE DELETED FIRST. A cached row is served whenever its `computed_for_date` matches the reader's today, and V43's rows carry
-- no best-day figure at all. Left in place, every user who had opened the Stats page earlier the same day would be served a NULL best_day and a
-- best_day_count of 0 - a subject with years of history reporting that its busiest day never happened - until the date rolled over or a write
-- invalidated them. Deleting them makes the very next read a miss, which recomputes: this is a cache, so the whole cost of emptying it is the ~14 to
-- ~71 ms recompute V43 measured, paid once per user. It is also what lets both columns be added straight to their final shape, with best_day_count
-- NOT NULL and carrying no DEFAULT, rather than back-filling a value that would have been wrong.
--
-- FAILURE MODE. If this DELETE were omitted, nothing would fail loudly - the columns would be nullable-shaped in practice and the page would render
-- a zero. SubjectStatsCacheIT is what covers the figures a cached read serves matching the computed ones per write path, and SubjectStatsCachingTest
-- covers the row/record round-trip carrying every figure, this one included.
DELETE FROM subject_stats_cache;

ALTER TABLE subject_stats_cache
    -- The busiest single day, and how many times the subject was recorded on it. Null exactly when best_month is - both mean "no history at all" -
    -- and the date is the EARLIEST day holding the count, so a record is dated to when it was set rather than to when it was last equalled.
    ADD COLUMN best_day       DATE,
    ADD COLUMN best_day_count BIGINT NOT NULL;
