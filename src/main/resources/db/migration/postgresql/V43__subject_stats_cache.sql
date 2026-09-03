-- Caches the Stats page's computed figures per (user, subject), so a page view reads its subjects' rows instead of re-aggregating the user's whole
-- history on every render.
--
-- WHAT IT REPLACES. StatsService.forAllSubjects issues ALL_DAILY_TOTALS_JPQL - a rollup over EVERY log the user has ever written, because streaks,
-- gaps and days-with-multiples are defined over all history and so have no [:from, :to] to bound them - and then assembles the figures in Java.
-- Measured on PostgreSQL 18.6 at 485,450 action_logs rows across 302 accounts, warm, with the V37/V39 index-only path in place:
--
--                                                  query    assemble()   total
--     30 actions x 3 years   (32,850 rows)         9.3 ms      3.7 ms    ~14 ms
--     50 actions x 10 years  (182,600 rows)       51.0 ms     18.6 ms    ~71 ms
--
-- Reading this table instead measured 0.10-0.17 ms at both sizes - it is O(subjects), not O(history), so it barely moves between them. At 296
-- bytes/row a 1,000-account deployment holding 30 subjects each is ~8.7 MB, against 95 MB for action_logs at that size.
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
-- (StatSubject.NOTES_ID), which no action row can ever carry. The user_id cascade below is what removes an account's rows.
--
-- FAILURE MODE. Every read checks `computed_for_date` against the user's today and recomputes on a mismatch, so a stale or missing row costs the ~14
-- to ~71 ms it costs today. Nothing here can serve a WRONG figure without the invalidation having been missed on a write path, which is what
-- SubjectStatsCacheIT covers per path.
CREATE TABLE subject_stats_cache (
    user_id                 UUID   NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- An action's id, or StatSubject.NOTES_ID (the nil UUID) for the user's day notes.
    subject_id              UUID   NOT NULL,
    computed_for_date       DATE   NOT NULL,

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

    -- The best month as its first day, since PostgreSQL has no year-month type; SubjectStats carries a YearMonth and the entity converts. Null when
    -- the subject has no history at all, which is the same case that leaves best_year null.
    best_month              DATE,
    best_month_count        BIGINT NOT NULL,
    -- The year itself, not the "2025"/"—" label SubjectStats renders: the label is rebuilt on read, so no presentation string is stored.
    best_year               INTEGER,
    best_year_count         BIGINT NOT NULL,

    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT subject_stats_cache_pkey PRIMARY KEY (user_id, subject_id)
);

-- Every read is "this user's rows, for this date", and every invalidation is "this user's rows" - both lead with user_id, which the primary key
-- already serves. No second index is warranted, for the reason V37 records for action_logs: the key's own index answers the access pattern.
