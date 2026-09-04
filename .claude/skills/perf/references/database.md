# Indexes, queries and N+1

**From the schema review that produced `V38`/`V39`** — measured with `EXPLAIN (ANALYZE, BUFFERS)` on PostgreSQL 18.6
at 323,154 `action_logs` rows (one 30-action, 3-year account among 199 lighter ones) and 12,182 notes:

- **A GIN index for the notes search is not possible, and would target the wrong 6% if it were.** `content_encrypted`
  is ciphertext, so a GIN needs either plaintext or deterministic per-word tokens — the frequency-analysis exposure
  `NOTES.md` already records as rejected. And the database is not the cost: a whole-journal search over 1,096 notes
  measured **1.6 ms** to read the sealed rows, **3.5 ms** to open them all (AES-256-GCM), **2.0 ms** for the
  substring match and **~20 ms** for `NoteSearch.suggest`. A search that finds something costs ~7 ms; one that finds
  nothing costs ~27 ms, nearly all of it the "did you mean". If notes search is ever reported as slow, `suggest` is
  the thing to look at — not the query, and not an index.
- **A GIN index for action filtering has nothing to gain either.** That filtering is done in Java over ~30 rows, and
  `actions_user_name_unique (user_id, name)` already answers every access index-only.
- **`INCLUDE (updated_at)` on the change-signature (ETag) indexes is not worth it**, though those queries run on
  every conditional GET: `ActionLog.rangeVersion` measured 0.33 ms, `Note.version` 0.43 ms, `Note.rangeVersion`
  0.06 ms, `Action.userVersion` 0.05 ms. All already sub-millisecond.
- **`INCLUDE (action_id, count)` on `idx_action_logs_user_date`** would make the dashboard's three-month warm-up
  index-only, but measured 0.77 ms -> 0.55 ms for **+11 MB** of index. Rejected on that ratio.
- **The query layer has no N+1.** `StatsService` (both the day/month summaries and the frequency chart), the admin
  user list via `SessionActivityService.recentActivityByUser` and both calendar feeds all batch already. The app has
  essentially no JPA relations, which is what keeps it that way.
- **The one N+1 that did exist has been fixed**: `Session.user` is the only `@ManyToOne` in the app, so it defaults to
  `FetchType.EAGER`, and an eager to-one on an HQL root is resolved by a SECOND statement rather than by a join — so
  `Session.findByTokenHash` cost two round trips on every authenticated request. Confirmed by running `SessionStoreIT`
  with `org.hibernate.SQL` at `DEBUG` (a `sessions` select followed by a `users where id=?` select), fixed with a
  `JOIN FETCH s.user`, and re-verified the same way: one statement, zero standalone user lookups. **That DEBUG-log
  run is the way to check this class of bug** — the relation count is low enough that nothing else is at risk today,
  but a second `@ManyToOne` added anywhere would have the same default.
  **That fix was only half the job, and the same DEBUG-log run found the other half**: the row the `JOIN FETCH`
  loaded was then thrown away, because `PostgresSessionStore.resolve` was `@Transactional` and so read it into a
  transaction-scoped persistence context that closes at commit — leaving the resource's first `CurrentUser.get()` to
  read the identical row again. EVERY authenticated request paid it: `GET /api/v1/users/me` and `GET /settings` were
  2 statements of which both were auth, `GET /` 4 of which 2, and one dashboard view is 4-8 such requests. Dropping
  that `@Transactional` moves the read into the REQUEST-scoped persistence context, which outlives authentication, so
  `CurrentUser` answers from the first-level cache with no statement at all (measured 2 -> 1 and 4 -> 3; `resolve`'s
  two writes moved to short programmatic transactions carrying one bulk statement each, and
  `AuthenticationQueryCountIT` pins the account to ONE load per request so re-adding the annotation fails the build).
  **One statement is the floor without caching the session row itself**, and a token cache was measured against that
  and REJECTED: it saves only the remaining lookup (0.46 ms locally, 1.24 ms at 4.78M rows) and can reach zero
  statements only by caching roles and identity state — exactly what `Session`'s "roles are resolved live" design
  forbids — while its eviction surface includes the invisible `sessions.user_id ON DELETE CASCADE`. Trigger for
  revisiting: a SECOND application instance, at which point the answer is a Redis `SessionStore` (the interface
  exists for precisely that), not a cache in front of `CurrentUser`, because an in-JVM cache makes revocation stop
  working in the very deployment that motivated it.
- **`users.created_at` was the deferred index whose trigger actually fired** - `V42` adds it. It was correctly not
  worth it at 200 and at 1,000 accounts (1.17 ms); at 50,000 the admin list's first page was 13-15 ms and its last
  page 127 ms, because the page was `Seq Scan` + top-N heapsort over every account. With the index: 0.2 ms and
  6.5 ms. **The lesson is the deferral note's own advice - re-measure at the size that matters rather than assuming
  the earlier figure still holds.** `ip_lockouts.ip_address` remains unindexed and remains fine: that table is pruned
  to a week of lockouts, so it does not grow with usage at all.
- **`notes` was measured for the same natural-key change `V39` made to `action_logs` and deliberately left alone**:
  its ~1.5 KB ciphertext payload dominates, and the table came out at 20 MB with or without the surrogate id.
- **The frequency chart now reads only the window it draws.** Its monthly rollup used to cover the subject's whole
  history so the caller could keep the anchor year's twelve months out of it, and its navigation bound
  (`earliestLoggedMonth`) was the minimum of that same rollup - so the MONTH view paid for a whole-history read it
  drew none of. The rollup is now range-bound (~65 ms -> 1.3 ms for three subjects) and the bound is its own query.
  **That bound is a `LATERAL` per subject on purpose**: a plain `SELECT MIN(log_date) ... WHERE action_id IN (...)`
  cannot use the index, because PostgreSQL will not push the `MIN` into each branch of the nested loop and reads
  every row instead (32.3 ms). Asked once per subject it is one index probe each (0.27 ms), and a chart holds at most
  `FrequencyCharts.MAX_SERIES` of them. **A denormalised "first logged" column was considered and rejected**: it
  would save ~0.3 ms, would need maintaining on six write paths, would still need this query to recompute whenever
  the earliest entry is deleted, would not cover the notes subject, and would fail silently (a stale bound blocks or
  invents chart navigation rather than erroring).
- **The notes search is the one path that grows and that NO index can reach**, because the content is ciphertext.
  Its cost is JVM-side and linear in journal length; measured over sealed 1.5 KB notes (open + match + the
  `NoteSearch.suggest` miss path):
  3 years/1,096 notes = 4.3 + 0.9 + 17.0 = **22 ms**; 10 years/3,652 = 8.7 + 3.6 + 54.0 = **66 ms**;
  20 years/7,300 = 17.2 + 5.5 + 125.0 = **148 ms**; 30 years/11,000 = 29.2 + 11.2 + 178.3 = **219 ms**.
  The database part is negligible throughout (3.2 ms to read a 10-year journal's 5,350 kB of ciphertext).
  **`suggest` is 70-80% of it and runs ONLY when a search matched nothing**, so a hit stays cheap (~40 ms even at
  30 years) and a miss is what degrades. An early exit on the first distance-1 candidate would cut it, but it would
  change WHICH word is suggested (ties currently break on occurrence count across the whole journal), so that is a
  behaviour decision rather than a free optimisation - do not take it as a pure perf change. Trigger: a user with a
  many-year journal reporting that a fruitless search feels slow.
- **Everything outside the Stats page and that search holds up at 10x the baseline** and needs no further indexing. Measured at
  4.78M `action_logs` rows / 1,000 accounts / a 10-year account, all sub-2 ms: the dashboard's three-month warm-up
  (0.33 ms), the frequency chart's month window (1.5 ms), the whole-journal notes read at 3,652 notes (1.06 ms), the
  session lookup (1.24 ms) and all four ETag signatures. **The admin user list at 1,000 accounts is 1.17 ms**, so at
  that size the `users.created_at` index was not warranted - re-measure before adding an index rather than assuming
  the row count alone is the trigger. **SUPERSEDED for that one index**: the bullet above records the trigger firing
  at 50,000 accounts, where the same page was 127 ms, and `V42` added it. The 1.17 ms figure and the advice to
  re-measure both still stand; only the "not warranted" conclusion was overtaken.
