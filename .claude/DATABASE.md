# Database: Schema, Migrations, Queries & the Vendor Seam

> **This file is ~19 KB. Read only the section you need** - `grep -n '^#' .claude/DATABASE.md` for its
> line range, then read that range rather than the whole file.
>
> - **Schema at a glance**
> - **Migrations** — Migrations are immutable, Conventions a new migration follows, Which migration records which decision
> - **Writing a query** — 1. Decide where the statement lives, 2. Bind every named parameter through a typed token, 3. Give a multi-column read a
>   projection record, 4. Invalidate the stats cache if you wrote anything, 5. Which test will fail if you skip a step
> - **The vendor seam**
> - **Known traps**
> - **Testing against a database**

> The persistence layer's rules in one place. **Read this before adding a migration, writing a query, touching
> `net.zodac.diurnal.persistence`, or changing anything under `src/main/resources/db/migration/`.**

Transaction ownership (who carries the `@Transactional`) stays in [`CLAUDE.md`](CLAUDE.md) — it is a rule about
resources and services as much as about the database. Performance measurements, and the optimisations that were
measured and *rejected*, are in the `perf` skill (`.claude/skills/perf/SKILL.md`); read that before proposing an
index or a query change.

## Schema at a glance

Eight entities, all Panache, all in the package that owns the feature — there is no `entity` package and no shared
base class beyond `PanacheEntityBase`.

| Table                 | Entity              | Package        | Notes                                                         |
|-----------------------|---------------------|----------------|---------------------------------------------------------------|
| `users`               | `User`              | `user`         | Two jsonb columns (`stats_fields`, `page_sizes`)              |
| `actions`             | `Action`            | `action`       | `actions_user_name_unique (user_id, name)` answers every read |
| `action_logs`         | `ActionLog`         | `log`          | Natural PK `(user_id, action_id, log_date)` since `V39`       |
| `notes`               | `Note`              | `note`         | `content_encrypted bytea`; no plaintext column since `V28`    |
| `user_notes_keys`     | `UserNotesKey`      | `note`         | Per-account data key, wrapped under the configured master key |
| `sessions`            | `Session`           | `auth.session` | The app's ONLY `@ManyToOne` — see "Known traps"               |
| `ip_lockouts`         | `IpLockout`         | `auth.lockout` | Pruned to a week; deliberately unindexed on `ip_address`      |
| `subject_stats_cache` | `SubjectStatsCache` | `stats.cache`  | One row per `(user, subject)`; a sink, depends on nothing     |

The app has essentially **no JPA relations** (`Session.user` is the only one), which is what keeps the query layer
free of N+1s. Account deletion is carried by `ON DELETE CASCADE` from `users(id)` — eight such clauses across the
migrations — not by application code walking the tables.

## Migrations

Flyway scripts live in `src/main/resources/db/migration/postgresql/`, sequential (`V1__`, `V2__`, …).
`quarkus.flyway.locations` is `classpath:db/migration/${quarkus.datasource.db-kind}`, so the datasource and the
migrations can never disagree, and a second vendor adds a **sibling directory** rather than branching inside these.
Flyway records a script by its name relative to the location root and matches on version + checksum, **not** on path
— verified by pointing the app at a database whose history was written under the old flat `db/migration/` path: all
42 migrations validated and the app booted clean, so that move was transparent to existing deployments.

`quarkus.hibernate-orm.schema-management.strategy=none` — Hibernate generates and validates nothing. **Flyway owns
the schema outright.**

### Migrations are immutable

> **NEVER modify an existing migration file — not the SQL, not even a comment or a whitespace. This is absolute: it
> applies to brand-new/uncommitted migrations, to "minor" tweaks, to fixing a typo, and to reverting a change you
> just made. ALWAYS express any change — including a reversion — as a NEW `V{n+1}__` file.**
>
> Flyway records a checksum of every applied migration and validates it at every startup. The instant a migration
> file's bytes change after it has been applied to *any* database (including a local/dev one that has already run
> it), that database fails to boot with a `Migration checksum mismatch` — recovering then requires a manual
> `flyway repair` or hand-editing `flyway_schema_history`. To change a column you already shipped in `V{n}`, add
> `V{n+1}` with the `ALTER`. To undo `V{n}`, add `V{n+1}` that reverses it. Treat every migration file as immutable
> the moment it exists.

**This one is enforced, not just stated.** `.claude/hooks/guard-protected-paths.sh` is a `PreToolUse` hook that
blocks an `Edit`/`Write` against any file that already exists under `src/main/resources/db/migration/`. Creating a
new `V{n+1}` script is allowed and is the only sanctioned way to express a change.

**`V40` is the worked example, and it is worth reading before arguing with the rule.** It changes no schema at all:
it exists only to correct two statements `V39` made in its own header comment. Amending `V39` in place would have
been the smaller diff and the wrong move — Flyway checksums the bytes, so an edit after the file has run anywhere
turns the next startup into a `Migration checksum mismatch`. The correction went into a new file beside the thing
it corrects, and `V39` stays exactly as it was applied. **The rule covers a comment exactly as it covers a
statement.**

### Conventions a new migration follows

Every one of these is followed consistently across the 43 existing scripts, so a new one that departs from them
looks wrong on sight:

- **File name**: `V{n}__snake_case_summary.sql`, describing the effect (`V42__users_created_at_index.sql`), not the
  ticket or the date.
- **A header comment carries the reasoning, and it is expected to be long.** The shape the recent migrations use:
  a one-line summary of what the script does; then *what it replaces* and why the previous shape was wrong; then
  the **measurements** that justify it, as a small table where there is more than one figure; then the **failure
  mode** — what goes wrong if the change is subtly incorrect, and which test covers it. `V43` is the fullest worked
  example, `V37`/`V39`/`V41`/`V42` the next fullest. **Write this comment for the person who will ask "why is it
  like this?" two years from now** — it is the only place that answer will exist.
- **Index naming**: `idx_<table>_<columns>` (`idx_action_logs_user_date`, `idx_users_created_at`).
- **Constraint naming**: `<table>_<columns>_unique`, `<table>_pkey`, and `<table>_<column>_<rule>` for a `CHECK`
  (`action_logs_count_range`, `notes_content_not_blank`).
- **Do not add an index the primary key already answers.** Both `notes` (`V26`) and `subject_stats_cache` (`V43`)
  record this explicitly: every access leads with `user_id`, which the key's own index already serves. `V37` exists
  precisely because `V19` added an index that duplicated a unique constraint's backing index.
- **Types**: `DATE` for a user-facing day boundary (a note's date, a log's date, `computed_for_date`), `TIMESTAMPTZ`
  for an audit stamp, and `TIMESTAMPTZ NOT NULL DEFAULT NOW()` for `created_at`/`updated_at`.
- **Store numbers and dates, never rendered text.** `V43` stores `best_year` as an `INTEGER` rather than the
  `"2025"`/`"—"` label the page shows, and stores no action name or colour at all, so a rename or a colour change
  touches no cached row. A stored presentation string is a stored translation bug.
- **A half-open day span is two columns**, `_start` and `_end` (exclusive) — never a day count. See the `DaySpan`
  rule in [`CLAUDE.md`](CLAUDE.md)'s invariants.
- **No vendor type name in a `columnDefinition`.** The two jsonb columns map through `@JdbcTypeCode(SqlTypes.JSON)`;
  the `columnDefinition = "jsonb"` they used to carry was DDL-only dead weight (Hibernate generates nothing here)
  and filed an entity under "rewrite this per vendor".

### Which migration records which decision

The reasoning behind the schema lives in the migration headers. This is the index into them, so a question that has
already been answered is not re-litigated from scratch:

| Migration | The decision it records                                                                                      |
|-----------|--------------------------------------------------------------------------------------------------------------|
| `V26`     | Why `notes` gets no second index — the primary key already leads with `user_id`                              |
| `V28`     | Dropping `notes.content`: note content is encrypted at rest, so there is no plaintext column to index        |
| `V37`     | `V19`'s index duplicated `action_logs_unique`'s backing index; dropping it made the Stats rollup index-only  |
| `V38`     | `action_logs.action_id`: the one FK whose child side had no index, so an action delete scanned the table     |
| `V39`     | Dropping `action_logs.id` for the natural key — no code path ever looked a log up by id                      |
| `V40`     | A comment-only migration correcting `V39`'s own header — the worked example of the immutability rule         |
| `V41`     | `actions.user_id`: turned the Stats rollup's disk-spilling sort into an Incremental Sort (~105 ms → ~59 ms)  |
| `V42`     | `users.created_at`: deferred twice at 1,000 accounts, warranted at 50,000 (127 ms → 6.5 ms on the last page) |
| `V43`     | `subject_stats_cache`: why `computed_for_date` is a column and not part of the key; why no second index      |

## Writing a query

### 1. Decide where the statement lives

**Default to JPQL in the entity's `*Queries` class** (`log/ActionLogQueries`, `note/NoteQueries`). Hibernate renders
JPQL for whichever dialect is configured, so a JPQL statement is portable for free and duplicating it per vendor
would be a portability *cost*.

**Only a statement JPQL genuinely cannot express goes behind the vendor seam** (`persistence.LogStatements` /
`persistence.NoteStatements`). **Before adding a method there, check the ORM cannot say it.** Four statements were
removed from that interface on exactly this test — `selectCount` (now `ActionLogQueries.ENTRY_COUNT_JPQL`) and the
decrement's `selectForUpdate`/`deleteEntry`/`decrementUpdate` arms, which were plain ANSI carried along by the
locking read beside them. The row lock in particular is `LockModeType.PESSIMISTIC_WRITE` on a `findById`, and
Hibernate knows each dialect's locking clause (`FOR UPDATE` on PostgreSQL, `WITH (UPDLOCK, ROWLOCK)` on SQL
Server) — a statement for it would be a vendor spelling the ORM already owns.

### 2. Bind every named parameter through a typed token

> **A query's named parameters MUST be bound through a typed `persistence.QueryParameter` token — NEVER a bare
> string** (no `.setParameter("userId", …)`). The name inside the quotes compiles whatever is typed, so a slip
> surfaces only when that query first runs, which for the upserts and row locks means it surfaces in a mutation
> path rather than in a test. Declare the token beside the query text it belongs to (`ActionLogQueries`/
> `NoteQueries`, or a `private static final` on the class holding an inline query), **typed with the value it
> takes** (`QueryParameter<UUID>`, `QueryParameter<Collection<UUID>>` — a type argument rather than a `Class`
> token, since a `Class` cannot express a parameterised type), and bind it through `JpqlQuery`/`SqlQuery`: a
> misspelled name — or a value of the wrong type for it — is then a compile error. `bind(QueryParameter<T>, T)` is
> what enforces the second half, so a new token must carry its type argument or it degrades to `Object` and
> enforces nothing.

The tokens stay in `ActionLogQueries`/`NoteQueries` and are **shared by every vendor implementation** — a
placeholder name is part of the contract, not a vendor's choice. Each seam method's Javadoc records the exact set
its statement must declare.

### 3. Give a multi-column read a projection record

> **Multi-column query projections MUST be a typed record via a JPQL `SELECT new <fqcn>(…)` constructor expression
> — NEVER a positional `Object[]` tuple** (no `(Object[]) …getSingleResult()` / `.getResultList()` then
> `row[0]`/`row[1]` casting). The `Object[]` form is untyped, re-orders silently, and needs manual casts; it was
> deliberately removed project-wide. Add a top-level record next to the query (see `MonthlyActionTotal`,
> `ActionPerformedDate`, `http.ChangeSignature`), pass its class to `createQuery(jpql, X.class)`, and let a nullable
> component (`@Nullable Instant`) carry a possibly-absent aggregate (`MAX(...)` over an empty set). Single-column
> scalar reads (a lone `COUNT`/id column) stay as-is — this rule is about **multi-column** rows only.

A projection record is a record, so the [`CLAUDE.md`](CLAUDE.md) rule about `*Extensions` applies to it: derived
logic goes in a `<Type>Extensions` class, because PITest cannot hot-swap mutants into a record.

### 4. Invalidate the stats cache if you wrote anything

Any write to an action, a log or a note changes figures the Stats page caches. There are **nine
`SubjectStatsCache.invalidate(userId)` call sites across five services** — `ActionService`, `LogService`,
`NoteService`, `ImportService` and `AdminUserService` — and `SubjectStatsCacheIT` covers each path end-to-end. A
new write path needs its own call and its own case there.

The cache is deliberately hook-invalidated rather than self-validating: a change-signature approach was built as
far as measurement and rejected, because the signature is itself a whole-history aggregate over the rows the cache
exists to avoid reading. That measurement is in the `perf` skill.

### 5. Which test will fail if you skip a step

| Rule                                          | Guard test                                         |
|-----------------------------------------------|----------------------------------------------------|
| No bare-string parameter binding              | `persistence/QueryBindingsAreTypedTest`            |
| The `:name` text matches the declared tokens  | `log/ActionLogQueriesTest`, `note/NoteQueriesTest` |
| Stats cache invalidated on every write path   | `stats/SubjectStatsCacheIT`                        |
| Decrement's lock ordering under a mixed flush | `log/DecrementLockIT`                              |
| A user id never reaches a log line            | `LogsIdentifyUsersByEmailTest`                     |
| Note content/search terms never reach a log   | `SecretsStayOutOfLogsTest`                         |

`ActionLogQueriesTest`/`NoteQueriesTest` instantiate the `Postgres*` classes directly — no CDI container, no
database — so they are cheap to run and a second vendor implementation is pinned by adding its cases there.

## The vendor seam

**Every SQL statement in the app that JPQL cannot express lives behind `persistence.LogStatements` or
`persistence.NoteStatements`, implemented by `persistence.postgres.PostgresLogStatements`/`PostgresNoteStatements`.**
Six statements in total, and that is the floor: the two `action_logs` upserts, its bulk `unnest` write, the
`CROSS JOIN LATERAL` earliest-logged probe, plus the two `notes` upserts. Three idioms — `ON CONFLICT`, `unnest`
over parallel arrays, and one `LATERAL` written to force a plan — over the two tables that are written hot.
Supporting a second database means adding an implementation and a `db/migration/<db-kind>/` directory — **not
editing an entity**.

- **The implementation is chosen by `@IfBuildProperty(name = "quarkus.datasource.db-kind", ...)`**, so the
  statements and the datasource can never disagree. `@LookupIfProperty` was rejected: it makes a bean reachable
  *only* by programmatic `Instance<T>` lookup, which contradicts the constructor-injection rule in
  [`CODE_STYLE.md`](CODE_STYLE.md).
- **Panache entity statics take the statements as their first parameter** (`ActionLog.setCount(statements, …)`,
  `Note.upsert(statements, …)`) rather than reaching for the bean. A static method cannot be injected into, and an
  `Arc.container()` service locator inside an entity would defeat the "build it with `new` + stubs, no CDI
  container" property the same rule exists to protect. The four callers (`LogService`, `StatsService`,
  `NoteService`, `ImportService`) inject the interface through their existing `@Inject` constructors.
- **`auth.session.PostgresSessionStore` is NOT part of this seam and was deliberately left alone.** Despite the
  name it contains no PostgreSQL whatsoever — it is plain Panache/JPA and would run unchanged on any database
  Hibernate supports. Its name is aspirational for the *Redis* swap `SessionStore` exists for. Moving it into
  `persistence.postgres` would file the one genuinely portable class under "rewrite this per vendor".
- **What a second vendor still has to deal with beyond these six statements**: the migrations, which are
  essentially the whole of the real work.

## Known traps

- **An eager to-one on an HQL root costs a second statement.** `Session.user` is the only `@ManyToOne` in the app
  and so defaults to `FetchType.EAGER`; Hibernate resolves it with a *separate* select rather than a join, which
  made `Session.findByTokenHash` two round trips on every authenticated request. Fixed with a `JOIN FETCH s.user`.
  **A second `@ManyToOne` added anywhere would have the same default.**
- **The way to check for that class of bug** is to run the relevant `*IT` with `org.hibernate.SQL` at `DEBUG`
  (`DB_LOG_LEVEL=DEBUG`, which also enables `org.hibernate.orm.jdbc.bind`) and read the statements actually issued.
  That run is what found both halves of the session N+1.
- **A `@Transactional` read throws its persistence context away at commit.** `PostgresSessionStore.resolve` used to
  be `@Transactional`, so the row its `JOIN FETCH` loaded was discarded and the resource's first `CurrentUser.get()`
  read the identical row again — on *every* authenticated request. Dropping the annotation moved the read into the
  request-scoped context, where `CurrentUser` answers from the first-level cache with no statement at all.
  `AuthenticationQueryCountIT` pins the account to ONE load per request, so re-adding it fails the build.
- **A pessimistic load of an already-managed instance takes the lock without re-reading the row.**
  `ActionLog.decrementCount` is the one `action_logs` write that takes no `statements` parameter — it is entirely
  ORM (a `PESSIMISTIC_WRITE` `findById`, then the new count assigned to the loaded entity, or `entity.delete()`).
  A caller that had hydrated an `ActionLog` for the same key earlier in the same transaction would therefore
  decrement from a stale count. Nothing does today (`LogService.adjust`'s guards load `Action` only), the method's
  Javadoc says so, and `DecrementLockIT` pins the mixed-flush orderings against a real database.

## Testing against a database

Integration tests (`*IT`) run against a real PostgreSQL managed by the Maven build's
`pre/post-integration-test` phases; unit tests (`*Test`) need no database. `IntegrationTestBase` provides
`newUser()`/`newAction()`/`newLog()`/`runInTx()` and the deterministic-time helpers. Full detail, including why the
dev database and the IT database are the same instance and what that breaks, is in [`TESTING.md`](TESTING.md).
