---
name: perf
description: The measured performance history - what was optimised, what was measured and REJECTED, and the trigger to revisit each. Read before proposing any optimisation. Use for: make it faster, this is slow, add an index or a cache, optimise, N+1, startup time, profiling.
---

# Performance: what has already been measured

**Do NOT re-derive these conclusions.** Every entry is a real measurement against the real image or a
realistically-sized database, and several record an optimisation that looked obviously correct and was measured to
be worthless or actively harmful. Each records the trigger that would justify revisiting it.

Two habits this history is worth adopting from:

- **Measure the thing the user experiences.** The AppCDS work was a 35% improvement in a number nobody sees.
- **"No index helps" is not the same as "this cost is inherent."** The monthly-totals query looked irreducible and
  turned out to be removable outright.

Schema and query rules are in [`DATABASE.md`](../../DATABASE.md); the procedure for making a change is the `db`
skill.

## Find your question here first

If the row says **rejected** or **already done**, the work is settled — read the linked file before arguing with
it, and do not start from scratch.

| Considering                                         | Verdict                                                                  | Detail                             |
|-----------------------------------------------------|--------------------------------------------------------------------------|------------------------------------|
| Class data sharing / AppCDS for faster boot         | **Built, measured, REMOVED** — 0.05s of user-visible gain                | [startup](references/startup.md)   |
| Moving startup work off the boot thread             | **Done** — the admin update check, ~0.3s of readiness                    | [startup](references/startup.md)   |
| Converting `Pages.slice` to `LIMIT`/`OFFSET`        | **Rejected** — measurably SLOWER at these sizes (two round trips)        | [caching](references/caching.md)   |
| Caching the Stats page's computed figures           | **Done** — `V43`'s `subject_stats_cache`, O(subjects) not O(history)     | [caching](references/caching.md)   |
| An in-JVM cache in front of the stats cache         | **Rejected** — saves 0.15 ms, breaks in the deployment that motivates it | [caching](references/caching.md)   |
| A self-validating cache keyed on a change-signature | **Rejected** — the signature costs 4.3-13.5 ms, more than it saves       | [caching](references/caching.md)   |
| A session-token cache                               | **Rejected** — trigger is a SECOND instance, and the answer is Redis     | [database](references/database.md) |
| A GIN index for notes search                        | **Impossible** (ciphertext) and aimed at the wrong 6% anyway             | [database](references/database.md) |
| A GIN index for action filtering                    | **Rejected** — done in Java over ~30 rows, already index-only            | [database](references/database.md) |
| `INCLUDE (updated_at)` on the ETag indexes          | **Rejected** — all four already sub-millisecond                          | [database](references/database.md) |
| `INCLUDE (action_id, count)` on the dashboard index | **Rejected** — 0.77→0.55 ms for +11 MB                                   | [database](references/database.md) |
| An index on `users.created_at`                      | **Done at 50,000 accounts** (`V42`) after being right to defer twice     | [database](references/database.md) |
| An index on `ip_lockouts.ip_address`                | **Not needed** — the table is pruned weekly and does not grow            | [database](references/database.md) |
| A natural primary key for `notes` (as `V39` did)    | **Rejected** — the ciphertext payload dominates; 20 MB either way        | [database](references/database.md) |
| A denormalised "first logged" column                | **Rejected** — saves ~0.3 ms, six write paths, fails silently            | [database](references/database.md) |
| Hunting an N+1                                      | **None left** — the one that existed is fixed; here is how to check      | [database](references/database.md) |
| Speeding up notes search                            | **The one path that grows and no index can reach** — `suggest` is 70-80% | [database](references/database.md) |

## Before adding an index

Read [`references/database.md`](references/database.md). Several indexes have been measured and rejected on their
cost/benefit ratio, and two were deferred, re-measured at a larger size, and only then added. **Re-measure at the
size that matters rather than assuming an earlier figure still holds** — that is the lesson `users.created_at`
records, where the same page was 1.17 ms at 1,000 accounts and 127 ms at 50,000.

## Before proposing any optimisation

State the measurement first. If there is no number, the change is a guess — and this file exists because several
guesses that everyone agreed were obvious turned out to be worth nothing.
