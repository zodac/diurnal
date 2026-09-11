-- The complete Diurnal schema, created in one script.
--
-- WHAT IT REPLACES. The 44 incremental migrations that built this schema between the first commit and the 1.0.0 release. They are gone, and this
-- file is the whole history: there is no upgrade path from a database built by them, because every one of those databases belongs to a pre-1.0.0
-- version that was never published as a Docker image (the publish workflow gated the image push on `major >= 1`, so 0.x existed only as a GitHub
-- release and as developers' own dev databases). Collapsing at exactly this point is what makes that free - 1.0.0 is the first release anyone can
-- actually be running, so there is nobody to migrate.
--
-- What the 44 scripts cost was paid on every single boot, forever, to describe a schema no live database was ever going to be built from
-- incrementally again: Flyway validated 44 checksums at startup, and a new deployment executed 44 scripts to reach a state this one reaches in a
-- single pass - creating `users.dark_mode` only to drop it for `theme` (V6/V9), widening `action_logs.count` twice (V4/V5/V15), narrowing
-- `display_name` twice (V24/V25), writing every note in plaintext before encrypting the column (V26/V28), and adding then dropping a duplicate index
-- on `action_logs` (V19/V37). None of that is schema; it is the path taken to the schema.
--
-- THE RATIONALE IS NOT LOST. The reasoning those 44 headers carried was the valuable half of them, so every decision that is not obvious from the
-- DDL has been carried forward into the section comments below, beside the table or index it explains. Where a figure is quoted it is the
-- measurement the original migration recorded, at the size it recorded it. .claude/DATABASE.md indexes them.
--
-- FROM HERE THE ORIGINAL RULE APPLIES AGAIN, UNCHANGED. This file is immutable the moment any database has run it, and 1.0.0 ships it to real
-- deployments - so it is immutable now. Every subsequent change is a new V{n+1} script, including a reversion and including a correction to a
-- comment in this one. Flyway checksums the bytes and revalidates at every startup; an edit here after release turns the next boot of every
-- deployment into `Migration checksum mismatch`, recoverable only by a manual `flyway repair`. There will be no second collapse: this one was
-- possible only because the set of databases to migrate was empty, and after 1.0.0 it never is again. See .claude/CLAUDE.md.
--
-- FAILURE MODE. Hibernate validates nothing (`quarkus.hibernate-orm.schema-management.strategy=none`), so Flyway owns the schema outright and an
-- entity that disagrees with this file fails at the feature's first read, not at boot. The whole `*IT` tier is what covers that, and
-- TextFieldsSchemaIT specifically pins each `TextFields` bound to the width of the column its value is stored in.

-- gen_random_uuid(), used as the default for every surrogate key below.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- users
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- An account, plus every preference it carries. Preferences live on this row rather than in a side table because they are read as a unit: the
-- current-user lookup that renders any page needs all of them, and one row is one read.
--
-- `password_hash` and the OIDC pair are mutually exclusive in practice: connecting an identity provider removes the password outright, so there is
-- no hybrid password+OIDC state and no disconnect. Both are nullable because an account has exactly one of them.
--
-- `timezone` and `week_start` are NULL-means-derived, and deliberately share that shape: NULL timezone means "use the server default
-- (app.timezone)", NULL week_start means "follow the account's language", resolved from CLDR (Monday for en-GB/es-ES, Sunday for
-- en-US/ar-SA/ja-JP). Neither is backfilled with the value it resolves to today, so the automatic state has exactly one representation and an
-- account follows its language if that language later changes.
--
-- `stats_fields` and `page_sizes` are the two jsonb columns in the schema, and both are nullable for the same reason: NULL means "never customised"
-- and re-defaults. stats_fields is an ordered array of {key, enabled} objects (User.statsFields / StatFieldPref) holding every stat in the user's
-- arranged order, so a stat's position is stable whether it is shown or hidden, and a newly-added stat appears automatically for anyone who has not
-- touched the setting. page_sizes is an array of {section, pageSize} objects (User.pageSizes / PageSizePref) holding an entry ONLY for sections
-- given their own value; an absent entry means the same as a NULL column - follow the general `page_size`. Neither carries a `columnDefinition` on
-- the entity: they map through @JdbcTypeCode(SqlTypes.JSON), and naming `jsonb` there would file the entity under "rewrite this per vendor".
--
-- `display_name` is VARCHAR(50) to match TextFields.DISPLAY_NAME_MAX_LENGTH, sized so it always fits the desktop navbar, which renders it in full
-- beside the nav links with no truncation. TextFieldsSchemaIT fails in both directions if the bound and the column drift apart.
--
-- Every default here matches the constant the User entity assigns to a new row (UserSettings.DEFAULT_*), so an insert that omits a column and one
-- that spells it out agree. `note_colour`'s default is the green-600 the calendar's note marker was fixed at before it became a preference.
CREATE TABLE users (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email              VARCHAR(255) NOT NULL,
    display_name       VARCHAR(50)  NOT NULL,

    password_hash      VARCHAR(255),
    oidc_subject       VARCHAR(255),
    oidc_issuer        VARCHAR(255),
    role               VARCHAR(20)  NOT NULL DEFAULT 'user',
    last_login_at      TIMESTAMPTZ,

    language           VARCHAR(10)  NOT NULL DEFAULT 'en-GB',
    timezone           VARCHAR(64),
    week_start         VARCHAR(9),
    theme              VARCHAR(10)  NOT NULL DEFAULT 'system',
    font               VARCHAR(16)  NOT NULL DEFAULT 'nova',
    calendar_view      VARCHAR(10)  NOT NULL DEFAULT 'full',
    note_colour        VARCHAR(7)   NOT NULL DEFAULT '#16a34a',
    page_size          INTEGER      NOT NULL DEFAULT 5,
    page_sizes         JSONB,
    stats_fields       JSONB,
    show_stats_summary BOOLEAN      NOT NULL DEFAULT TRUE,
    show_note_counter  BOOLEAN      NOT NULL DEFAULT TRUE,
    decimal_places     SMALLINT     NOT NULL DEFAULT 1,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT users_email_unique UNIQUE (email)
);

-- Prevents the same identity-provider subject being registered twice, and is what the OIDC sign-in lookup keys on. Partial, so the password
-- accounts - which are every row with a NULL oidc_subject - are not indexed at all and cannot collide with each other.
CREATE UNIQUE INDEX idx_users_oidc
    ON users (oidc_issuer, oidc_subject)
    WHERE oidc_subject IS NOT NULL;

-- The admin user list is `User.findAll(Sort.by("createdAt")).page(...)`, and without this index PostgreSQL read every account row and top-N sorted
-- them to return 25 - a cost growing linearly with the size of the deployment, for a page whose content does not change size at all. This index was
-- twice looked at and twice DEFERRED at 1,000 accounts, where it measured 1.17 ms and was genuinely invisible; it was added on the numbers at
-- 50,000 accounts, where the plan goes from a Seq Scan feeding a top-N heapsort to an Index Scan that reads 25 entries and stops:
--
--                                     before        after
--     first page                    13-15 ms     0.2-0.3 ms
--     last page (offset 49,000)      127 ms       6.0-6.7 ms
--
-- 1,112 kB at that size, on a table written only on registration, on a settings save and on each login (`last_login_at`) - not a hot write path.
-- NOT redundant with users_email_unique or idx_users_oidc: an index only serves an ORDER BY when the ordering column leads it, and neither does.
--
-- The `SELECT count(*) FROM users` sizing the same page's pagination is left unindexed deliberately: it is an unfiltered exact count, which
-- PostgreSQL answers by scanning whatever indexes exist (5.8 ms at 50,000 accounts). The alternatives - an estimate from the statistics, or a
-- maintained counter - trade correctness or write cost for a figure that is only a page total.
CREATE INDEX idx_users_created_at ON users (created_at);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- actions
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- A habit the user tracks. There is no soft-delete flag: an action and its logs are hard-deleted, carried by the cascade on action_logs.action_id.
CREATE TABLE actions (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    colour     VARCHAR(7)   NOT NULL DEFAULT '#6366f1',
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- Its backing index on (user_id, name) is what Action.findByUser reads, in the order the Actions page wants them.
    CONSTRAINT actions_user_name_unique UNIQUE (user_id, name)
);

-- Lets the Stats page's whole-history rollup sort incrementally instead of sorting - and spilling - all at once.
--
-- ALL_DAILY_TOTALS_JPQL reads every log a user has ever written, ordered by (action_id, log_date). Each inner index-only scan of action_logs_pkey
-- ALREADY yields that order within its own action; the rows only arrive globally unsorted because the outer scan of `actions` returns them in
-- physical order. Given an index that hands the planner a user's actions in `id` order, the nested loop's output carries action_id as a sorted
-- prefix and PostgreSQL switches to an Incremental Sort - sorting one action's rows at a time rather than all of them together, which also puts the
-- sort back in memory. Measured at 50 actions x 10 years (182,600 rows), where the old sort no longer fit `work_mem`:
--
--                                   plan                        sort         total
--     before      Sort (external merge, 5,728 kB on disk)       105 ms       144 ms
--     after       Incremental Sort (presorted key action_id)     53 ms        91 ms
--
-- Repeated end-to-end runs: 109/101/107 ms before, 62/56/58 ms after - roughly 1.8x on the slowest page in the application, for 600 kB of index on a
-- table written only when someone adds, renames or deletes an action. It also gives `loggedActionIds` an index-only outer scan.
--
-- NOT redundant with actions_user_name_unique: that one orders by name, which says nothing about `id` order.
--
-- WHY THE QUERY'S ORDER BY IS NOT SIMPLY DROPPED, which would remove the sort outright: StatsService.groupDays buckets the rows per action and needs
-- each bucket in date order. Dropping the clause would leave that resting on the plan happening to emit them that way, and a later plan change would
-- then silently corrupt streaks and gaps rather than fail. The ordering stays a stated requirement of the query; this index makes it cheap.
CREATE INDEX idx_actions_user_id ON actions (user_id, id);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- action_logs
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- One row per (user, action, day), holding that day's tally. A count of zero is NO ROW, never a zero row.
--
-- THE PRIMARY KEY IS THE NATURAL ONE, and this table alone in the schema has no surrogate id. It carried a `UUID PRIMARY KEY DEFAULT
-- gen_random_uuid()` like every other table and earned nothing from it: no code path ever looked a log entry up by id - no finder, no /api/v1 field,
-- no template - and the upserts minted a fresh UUID per insert purely to satisfy the column. Confirmed under a realistic workload at 323,210 rows,
-- where the surrogate's index recorded 0 index scans against the natural key's 11,459. Dropping it, measured on those rows:
--
--                                heap     indexes    total
--     surrogate uuid PK          34 MB     47 MB     81 MB
--     natural PK                 26 MB     33 MB     59 MB
--
-- (Those are the two shapes built side by side. On an existing table a DROP COLUMN only marks the column dropped in the catalogue, so the heap
-- reclaim arrives gradually as rows are rewritten; the index saving is the larger half and is immediate. Immaterial here - this script builds the
-- table in its final shape and there is nothing to reclaim.)
--
-- `notes` was measured for the same change and deliberately left with its surrogate: its rows carry ~1.5 KB of ciphertext, which dominates
-- everything else, and the table came out at 20 MB either way.
--
-- THE INCLUDE (count) PAYLOAD makes the Stats rollups index-only. MONTHLY_TOTALS_JPQL / DAILY_TOTALS_JPQL / ALL_DAILY_TOTALS_JPQL filter on
-- (user_id, action_id) and then read `count`; without it in the index every matching row cost a heap fetch. The payload is free in storage terms -
-- `count` is a SMALLINT and fits in the index tuple's existing alignment padding. INCLUDE does not participate in the key, so the constraint still
-- enforces one row per (user, action, day) and `ON CONFLICT ON CONSTRAINT action_logs_pkey` in the increment/set upserts resolves against it.
-- (An index-only scan also needs a current visibility map, which is autovacuum's job; after a burst of writes the planner falls back to a heap scan
-- until the next vacuum. That is a graceful degradation, not a regression.)
--
-- The 999 ceiling is ActionLog.MAX_DAILY_COUNT: the web surface silently caps at it, the API rejects above it, and this CHECK is the backstop.
CREATE TABLE action_logs (
    user_id    UUID        NOT NULL REFERENCES users(id)   ON DELETE CASCADE,
    action_id  UUID        NOT NULL REFERENCES actions(id) ON DELETE CASCADE,
    log_date   DATE        NOT NULL,
    count      SMALLINT    NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT action_logs_count_range CHECK (count >= 1 AND count <= 999),
    CONSTRAINT action_logs_pkey PRIMARY KEY (user_id, action_id, log_date) INCLUDE (count)
);

-- The day panel and the calendar feeds read a user's logs for a date range, with no action predicate, which the primary key cannot serve - it leads
-- with user_id but then requires action_id before log_date.
CREATE INDEX idx_action_logs_user_date ON action_logs (user_id, log_date);

-- Indexes the one foreign key in the schema whose child side would otherwise have none. PostgreSQL enforces the action_id cascade with a query for
-- the children of the row being deleted, keyed on action_id ALONE - and both action_logs_pkey and idx_action_logs_user_date lead with user_id, which
-- a lone action_id predicate cannot use. Without this index every action delete fell back to a sequential scan of the entire table, so the cost was
-- proportional to the rows across EVERY account rather than to the logs of the action being removed: one user deleting an action scanned every other
-- user's history. Measured at 323,210 rows:
--
--     deleting an action with NO logs at all:  2.75 ms -> 0.30 ms
--     deleting an action with 1,096 logs:      3.93 ms -> 1.67 ms
--     the data import's clear-down (30):      21.4  ms -> 7.4  ms
--
-- The zero-log case is the one that proves it was a scan: without the index the cost is the same whether the action had a thousand log entries or
-- none, because the work is finding out that there are none. NOT redundant with the primary key: the application's own deletes
-- (ActionLog.deleteByAction) do pass a user_id and are served by it, but the FK trigger is generated by PostgreSQL and knows only the action. 2.3 MB
-- at the above size, ~7 bytes per row.
CREATE INDEX idx_action_logs_action_id ON action_logs (action_id);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- notes
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- One free-text note (a journal entry) per user per day, alongside the per-day tallies in action_logs. Unlike a log entry a note may be written for
-- ANY date, INCLUDING A FUTURE ONE: NoteService deliberately does not apply the LogGuards.isFuture rule, so the day panel's "actions can't be logged
-- for a future date" placeholder and a live note box coexist on the same day. An empty note is NO ROW, exactly as a count of zero is; saving blank
-- content deletes the row, and the CHECK enforces that at the storage layer so no path can introduce a blank one.
--
-- CONTENT IS ENCRYPTED AT REST and there is no plaintext column, by construction. The scheme is envelope encryption, two levels: every note is
-- AES-256-GCM sealed under a data key belonging to its owner, with the owner and the date bound in as associated data - so a ciphertext moved to
-- another day, or into another account's row, fails to open instead of silently decrypting somewhere it does not belong - and that per-user data key
-- is itself stored only wrapped, under NOTE_ENCRYPTION_KEY, which lives in CONFIGURATION and never in this database.
--
-- WHAT THAT BUYS: a dump, backup, replica or restored volume carries sealed notes and wrapped data keys and opens neither. Reading a note takes the
-- database AND the environment file - different accidents, rarely captured together. WHAT IT DOES NOT: an administrator who has the running server
-- has both, and can read notes. This is encryption at rest against losing one of the two, NOT protection from the operator, and must never be
-- described to a user as end-to-end or zero-knowledge. See .claude/NOTES.md.
--
-- There is no length column. The bound is TextFields.NOTE_MAX_LENGTH, read through note/NoteField and enforced by TextValidation before anything is
-- sealed; a ciphertext's length is a function of the plaintext's BYTES plus the IV and tag, so no column width could express a character bound even
-- in principle. TextFieldsSchemaIT asserts the plaintext column has not come back rather than pinning a width here.
CREATE TABLE notes (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    note_date         DATE        NOT NULL,
    content_encrypted BYTEA       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT notes_unique UNIQUE (user_id, note_date),
    -- A sealed value is always longer than its IV and tag, so a zero-length one cannot be a real note.
    CONSTRAINT notes_content_encrypted_not_empty CHECK (length(content_encrypted) > 0)
);

-- No separate index on notes: notes_unique's backing index is on (user_id, note_date), which is the exact leading-column order every read uses - the
-- single-day lookup, the calendar range scan and the range change-signature alike. A second index on the same columns would only cost write time.

-- Key material in its own table rather than on `users`: an account row is read and returned all over the application (the admin user list, the
-- profile endpoints, every current-user lookup), and none of those paths has any business carrying the thing that opens someone's journal. Created
-- with the account and never changed thereafter; removed with it by the cascade.
--
-- WHY A PER-USER KEY, when one application key would do: rotating NOTE_ENCRYPTION_KEY rewrites one small row per user rather than every note ever
-- written, and a future change of scheme re-wraps those same rows and leaves every sealed note untouched.
CREATE TABLE user_notes_keys (
    user_id     UUID        PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    dek_wrapped BYTEA       NOT NULL,
    key_version SMALLINT    NOT NULL DEFAULT 1,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT user_notes_keys_dek_not_empty CHECK (length(dek_wrapped) > 0)
);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- sessions
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- Server-side session store. A login mints a random opaque token; only its SHA-256 hash is stored here, so a read-only leak of this table yields no
-- usable sessions. Identity is resolved per request by hashing the presented token (cookie or Bearer) and looking it up; roles are read live from
-- the users row, so this table intentionally holds no role or permission state. Revocation is deleting a row.
CREATE TABLE sessions (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash   BYTEA       NOT NULL,
    user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    auth_source  VARCHAR(16) NOT NULL,               -- 'password' | 'oidc'
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), -- bumped per request; drives the idle timeout
    expires_at   TIMESTAMPTZ NOT NULL,               -- absolute cap (created_at + absolute lifetime)
    user_agent   TEXT,                               -- retained for a future "active sessions" view
    client_ip    VARCHAR(64),
    CONSTRAINT sessions_token_hash_unique UNIQUE (token_hash)
);

-- Revoke-all / revoke-others for a user, and the sweeper's expiry pruning.
CREATE INDEX idx_sessions_user_id    ON sessions (user_id);
CREATE INDEX idx_sessions_expires_at ON sessions (expires_at);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- ip_lockouts
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- Durable audit log of per-IP auth lockouts (a lockout tallies BOTH failed logins and failed registrations from one client IP; see IpThrottle). The
-- live lockout ENFORCEMENT stays in-memory (AttemptThrottle, resets on restart); this table is written only when a lockout trips, so an administrator
-- can review recent lockouts and manually clear one. A row is stamped unlocked_at / unlocked_by when an administrator manually unlocks the IP; a
-- naturally-expired lockout leaves its row untouched, its status derived from locked_until at read time. The admin view shows only the last week of
-- rows, and each new lockout prunes rows older than that window, so the table stays bounded.
--
-- There is deliberately no index on ip_address: nothing looks a lockout up by it. Every read is the history view.
CREATE TABLE ip_lockouts (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    ip_address    VARCHAR(64)  NOT NULL,
    locked_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    locked_until  TIMESTAMPTZ  NOT NULL,              -- locked_at + the configured lockout duration
    failure_count INTEGER      NOT NULL,              -- failures tallied for the IP when the lockout tripped
    unlocked_at   TIMESTAMPTZ,                        -- set when an administrator manually unlocks; NULL otherwise
    unlocked_by   VARCHAR(255)                        -- email of the administrator who unlocked; NULL otherwise
);

-- The history view orders by locked_at (most recent first) filtered to the retention window, and the per-lockout prune deletes on locked_at; both
-- are served by this index.
CREATE INDEX idx_ip_lockouts_locked_at ON ip_lockouts (locked_at);

-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- subject_stats_cache
-- ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
-- Caches the Stats page's computed figures per (user, subject), so a page view reads its subjects' rows instead of re-aggregating the user's whole
-- history on every render.
--
-- WITHOUT IT, StatsService.forAllSubjects issues ALL_DAILY_TOTALS_JPQL - a rollup over EVERY log the user has ever written, because streaks, gaps and
-- days-with-multiples are defined over all history and so have no [:from, :to] to bound them - and then assembles the figures in Java. Measured on
-- PostgreSQL 18.6 at 485,450 action_logs rows across 302 accounts, warm, with the index-only path above in place:
--
--                                                  query    assemble()   total
--     30 actions x 3 years   (32,850 rows)         9.3 ms      3.7 ms    ~14 ms
--     50 actions x 10 years  (182,600 rows)       51.0 ms     18.6 ms    ~71 ms
--
-- Reading this table instead measured 0.10-0.17 ms at both sizes - it is O(subjects), not O(history), so it barely moves between them. At 308
-- bytes/row a 1,000-account deployment holding 30 subjects each is ~9 MB, against 95 MB for action_logs at that size.
--
-- THE KEY IS (user_id, subject_id) AND `computed_for_date` IS DELIBERATELY NOT PART OF IT. A SubjectStats is a function of two inputs, not one: the
-- subject's dated entries, and "today" in the user's own timezone. currentStreak walks back from today, longestGap carries an open run up to it, and
-- the this/last month/year counts are all keyed off it - so the row goes stale when the date rolls over, with no write having happened, and every
-- user rolls over at their own moment. Carrying the date as a plain COLUMN and treating a mismatch as a miss makes that self-correcting: a stale row
-- is overwritten in place rather than accumulating a row set per user per day, so the table stays one row per (user, subject) forever and needs no
-- sweeper. Putting the date in the key instead would grow the table by a row set for every day any user opened the page.
--
-- NO NAME AND NO COLOUR ARE STORED - only the numbers. The subject's presentation is rebuilt live from `actions` (and from users.note_colour for the
-- notes subject), which keeps action renames, colour changes and the note-colour preference entirely off the invalidation surface: none of them
-- changes a single figure here. `subject_id` is therefore NOT a foreign key to actions.id - the notes subject uses the fixed nil UUID
-- (StatSubject.NOTES_ID), which no action row can ever carry. The user_id cascade is what removes an account's rows.
--
-- FAILURE MODE. Every read checks `computed_for_date` against the user's today and recomputes on a mismatch, so a stale or missing row costs the ~14
-- to ~71 ms it would have cost anyway. Nothing here can serve a WRONG figure without the invalidation having been missed on a write path, which is
-- what SubjectStatsCacheIT covers per path.
CREATE TABLE subject_stats_cache (
    user_id                 UUID    NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- An action's id, or StatSubject.NOTES_ID (the nil UUID) for the user's day notes.
    subject_id              UUID    NOT NULL,
    computed_for_date       DATE    NOT NULL,

    total_days              INTEGER NOT NULL,
    days_with_multiples     INTEGER NOT NULL,
    total_count             BIGINT  NOT NULL,

    first_performed         DATE,
    last_performed          DATE,
    last_day_with_multiples DATE,

    -- The three DaySpans, each stored as its half-open [start, end_exclusive) pair. A span is never null (an empty history yields a zero-length one
    -- anchored on today), so these six columns are NOT NULL.
    current_streak_start    DATE NOT NULL,
    current_streak_end      DATE NOT NULL,
    longest_streak_start    DATE NOT NULL,
    longest_streak_end      DATE NOT NULL,
    longest_gap_start       DATE NOT NULL,
    longest_gap_end         DATE NOT NULL,

    this_month_count        BIGINT NOT NULL,
    last_month_count        BIGINT NOT NULL,
    this_year_count         BIGINT NOT NULL,
    last_year_count         BIGINT NOT NULL,

    -- The busiest single day, and how many times the subject was recorded on it. The date is the EARLIEST day holding the count, so a record is
    -- dated to when it was set rather than to when it was last equalled.
    best_day                DATE,
    best_day_count          BIGINT NOT NULL,
    -- The best month as its first day, since PostgreSQL has no year-month type; SubjectStats carries a YearMonth and the entity converts.
    best_month              DATE,
    best_month_count        BIGINT NOT NULL,
    -- The year itself, not the "2025"/"-" label SubjectStats renders: the label is rebuilt on read, so no presentation string is stored.
    best_year               INTEGER,
    best_year_count         BIGINT NOT NULL,

    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- best_day, best_month and best_year are null in exactly the same case - the subject has no history at all.
    CONSTRAINT subject_stats_cache_pkey PRIMARY KEY (user_id, subject_id)
);

-- Every read is "this user's rows, for this date", and every invalidation is "this user's rows" - both lead with user_id, which the primary key
-- already serves. No second index is warranted, for the same reason `notes` gets none: the key's own index answers the access pattern.
