---
name: db
description: Procedure for any database change - Flyway migrations, queries, indexes, entities, or net.zodac.diurnal.persistence. Use for: add a column or table, new migration, JPQL or native SQL, add an index, schema change, Panache entity, upsert, parameter binding.
---

# Making a database change

Reference detail lives in [`DATABASE.md`](../../DATABASE.md) — schema inventory, the migration-rationale index, the
vendor seam in full, and the known traps. This is the procedure.

## Step 1 — Never edit an existing migration

To change anything a shipped migration created, add a **new `V{n+1}__` file**. This covers reversions, typos and
comment-only corrections, and it covers uncommitted migrations too (your local dev database has already run them).
Flyway checksums the bytes and revalidates at every startup, so an edit breaks the boot of every database that ran
the original.

A `PreToolUse` hook blocks the edit, so you will find out immediately rather than at the next boot. `V40` is the
worked precedent: it changes no schema and exists only to correct two sentences in `V39`'s header comment.

## Step 2 — Write the migration

`src/main/resources/db/migration/postgresql/V{n}__snake_case_summary.sql`, named for the effect
(`V42__users_created_at_index.sql`), not the ticket.

**The header comment is the deliverable as much as the DDL.** The shape the recent scripts use, and the one to
follow:

1. One line: what this script does.
2. What it replaces, and why the previous shape was wrong.
3. The measurements that justify it — a small table if there is more than one figure.
4. The failure mode: what goes subtly wrong if this is incorrect, and which test covers it.

`V43` is the fullest example; `V37`/`V39`/`V41`/`V42` are the next fullest. This comment is the only place the
"why" will exist in two years, so write it for the person who asks then.

Conventions the DDL follows — `idx_<table>_<columns>`, `<table>_<columns>_unique`, `<table>_pkey`, `DATE` for a
user-facing day boundary and `TIMESTAMPTZ` for an audit stamp, `ON DELETE CASCADE` from `users(id)` for account
deletion, half-open spans as `_start`/`_end` column pairs, no vendor type name in a `columnDefinition`, and **no
index the primary key already answers**. Full list with the reasoning: [`DATABASE.md`](../../DATABASE.md).

**Store numbers and dates, never rendered text.** A stored label is a stored translation bug and drags presentation
onto the invalidation surface.

## Step 3 — Update the entity

Panache entity in the package that owns the feature — there is no `entity` package. `schema-management.strategy` is
`none`, so Hibernate validates nothing: **the entity and the migration agreeing is on you**, and an `*IT` is what
proves it.

If the column is a **user preference**, it is not finished at the entity — see the `endpoint` skill for the rest of
the chain (`@Preference` → `UserDto.Preferences` → Settings row → message bundle → `UserPreferencesExposureTest`).

## Step 4 — Write the query

**Default to JPQL in the entity's `*Queries` class** (`ActionLogQueries`, `NoteQueries`). Hibernate renders it per
dialect, so it is portable for free.

**Only go behind the vendor seam** (`LogStatements`/`NoteStatements`) if JPQL genuinely cannot express it. Six
statements qualify today and that is the floor. **Check the ORM cannot say it first** — four statements were removed
from that interface on exactly this test, and the pessimistic row lock is `LockModeType.PESSIMISTIC_WRITE` on a
`findById`, which Hibernate already spells per dialect.

Two rules the compiler and a guard test enforce:

- **Bind through a typed `QueryParameter` token, never a bare string.** No `.setParameter("userId", …)`. Declare the
  token beside the query text, typed with the value it takes (`QueryParameter<UUID>`,
  `QueryParameter<Collection<UUID>>`), and bind through `JpqlQuery`/`SqlQuery`. A token without its type argument
  degrades to `Object` and enforces nothing.
- **A multi-column read gets a projection record** via `SELECT new <fqcn>(…)`, never an `Object[]` tuple. A nullable
  component carries a possibly-absent aggregate. Single-column scalar reads are exempt.

A projection record is a record, so derived logic goes in a `<Type>Extensions` class — PITest cannot mutate a
record.

## Step 5 — Invalidate the stats cache if you wrote anything

Any write to an action, a log or a note changes figures `subject_stats_cache` holds. Call
`SubjectStatsCache.invalidate(userId)` on the write path and add a case to `SubjectStatsCacheIT`. There are nine
such call sites across `ActionService`, `LogService`, `NoteService`, `ImportService` and `AdminUserService`.

## Step 6 — Decide who owns the transaction

The default is **the resource method owns the `@Transactional`** and the service assumes one is active. Read-only
endpoints carry none. The exception is the three hashing services (`AuthenticationService`, `RegistrationService`,
`PasswordChangeService`), which own their own short transaction and whose callers must therefore **not** be
transactional. Put `@RollbackOnErrorStatus` on any resource class with transactional write endpoints. Full rule:
[`CLAUDE.md`](../../CLAUDE.md).

**Do not make a read `@Transactional` to be tidy.** A transaction-scoped persistence context is discarded at
commit, so the rows it loaded are re-read by the request that follows — the bug `AuthenticationQueryCountIT` now
pins against.

## Step 7 — Verify

`.github/scripts/lint_and_tests.sh java` (see the `gate` skill). The checks that will catch a skipped step:

| Skipped step                         | What fails                                        |
|--------------------------------------|---------------------------------------------------|
| Bare-string parameter binding        | `QueryBindingsAreTypedTest`                       |
| Token/`:name` mismatch               | `ActionLogQueriesTest`, `NoteQueriesTest`         |
| Stats cache not invalidated          | `SubjectStatsCacheIT`                             |
| Entity disagrees with the migration  | The feature's own `*IT`, at boot or on first read |
| A user id in a log line              | `LogsIdentifyUsersByEmailTest`                    |
| Note content or a search term logged | `SecretsStayOutOfLogsTest`                        |

To see the statements actually issued — the way to catch an N+1 — run the relevant `*IT` with `DB_LOG_LEVEL=DEBUG`,
which enables `org.hibernate.SQL` and the bind parameters.

## Before adding an index

Read the `perf` skill first. Several indexes have been measured and **rejected** on their cost/benefit ratio, and
two were deferred, re-measured at a larger size, and only then added. Adding one without a measurement repeats work
that has already been done.
