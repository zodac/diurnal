# Pagination, the Stats page and every cache that was measured

**Do NOT re-derive these conclusions** — they were measured on the real image at 30 actions x 3 years (32,850
`action_logs` rows):

- **In-memory pagination (`Pages.slice`) is correct as it stands and must not be "fixed".** The lists using it are
  bounded by the action count (~15-30 rows; the actions list measured 13.3 ms), and the notes page already lets the
  database page an unfiltered listing. Converting them to `LIMIT`/`OFFSET` would need a `COUNT` plus a page query —
  two round trips where there is currently one, at ~4 ms each — and so would be measurably SLOWER at these sizes.
- **The Stats page is the path that actually degrades with time**, not either of the above: 89.8 ms, because
  streaks, gaps and days-with-multiples are defined over all history, so `ALL_DAILY_TOTALS_JPQL` rolls up every row
  on each view. `V37`'s `INCLUDE (count)` already made that query index-only, so the remaining cost is transferring
  and aggregating the rows in Java. It grows roughly 30 ms per year of history. The only real lever left is caching
  computed stats per user with invalidation on write; that is a large change for 90 ms, so the trigger is a user
  reporting the page feels slow, not a number in a profile.
  **Most of that figure has since been removed** and the remainder re-measured at a deliberately worse size
  (4.78M rows, 1,000 accounts, a 50-action x 10-year account = 182,600 rows): `V41` turned the query's `ORDER BY`
  from a disk-spilling sort into an Incremental Sort (~105 ms -> ~59 ms warm), and `LOGGED_ACTION_IDS_JPQL` went from
  scanning the whole history to probing per action (23.2 ms -> 0.25 ms).
  **`MONTHLY_TOTALS_JPQL` then left the Stats page altogether**, which is worth recording as a caution: it looked like
  an irreducible ~84 ms aggregate-over-all-history, and the earlier note here said so. It was not. A month's total is
  the sum of its days' totals, and `assembleAll` was ALREADY reading the daily rollup over the same rows - so the
  monthly query was a second whole-history aggregate producing a coarser view of data the caller had in hand.
  `StatsService.assemble` now derives the per-month and per-year figures in Java and the query is gone from that path
  (verified identical against the database: 6,050 rows each way, zero differing in either direction). **The lesson is
  that "no index helps" is not the same as "this cost is inherent"** - the fix was to stop issuing the query.
  What remains is the single daily rollup, which IS inherent: streaks, gaps and days-with-multiples are defined over
  all history. **That last lever has now been taken** - `V43` adds `subject_stats_cache`, one row per
  `(user, subject)` holding the computed figures, so the rollup and the Java assembly run once per user per day
  rather than once per view. Re-measured on PostgreSQL 18.6 at 485,450 `action_logs` rows across 302 accounts, warm:
  a 30-action x 3-year account (32,850 rows) is 9.3 ms of query plus 3.7 ms of `assemble()` = ~14 ms, and a
  50-action x 10-year one (182,600 rows) is 51.0 ms plus 18.6 ms = ~71 ms. Reading the cache instead is
  **0.10-0.17 ms at both sizes** - it is O(subjects), not O(history) - at 296 bytes/row, so 1,000 accounts holding
  30 subjects each is ~8.7 MB against 95 MB of `action_logs`.
  **`computed_for_date` is a COLUMN, not part of the key**, because a `SubjectStats` depends on the user's "today" as
  much as on their entries (the current streak walks back from it, the longest gap runs up to it, the this/last
  month and year counts are keyed off it) and every user rolls over at their own moment. A mismatch is a miss and the
  row is overwritten in place, so the table stays one row per `(user, subject)` and needs no sweeper; putting the date
  in the key would add a row set for every day any user opened the page.
  **An in-JVM cache was measured against it and rejected**: it saves the remaining 0.15 ms, and would stop working in
  the very deployment (a second app instance) that would motivate it - the same reasoning the session-token cache
  note above records.
  **A self-validating cache keyed on a change-signature was built as far as measurement and rejected too**, which is
  worth recording because it is the more obviously correct design: storing the user's `COUNT(*) + MAX(updated_at)`
  and comparing it on read needs no invalidation hooks at all and cannot go stale-wrong. But that signature is a
  whole-history aggregate over the same rows the cache exists to avoid reading - measured **4.3 ms** at 32,850 rows
  and **13.5 ms** at 182,600, against 0.15 ms for the plain read - and it cannot be made cheap: the planner takes
  `idx_action_logs_user_date` and fetches the heap for `updated_at`, and adding `updated_at` to the primary key's
  `INCLUDE` did not change the plan or the timing. It would have given up 80-95% of the win to remove eight
  one-line hooks, so the hooks stayed and `SubjectStatsCacheIT` covers each of them end-to-end instead.
