/*
 * BSD Zero Clause License
 *
 * Copyright (c) 2026-2026 zodac.net
 *
 * Permission to use, copy, modify, and/or distribute this software for any
 * purpose with or without fee is hereby granted.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY
 * SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR
 * IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package net.zodac.diurnal.log;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;
import net.zodac.diurnal.persistence.QueryParameter;

/**
 * The handwritten SQL and JPQL queries backing {@link ActionLog}'s static finder and mutation methods, held here as named constants to keep the
 * entity itself readable. Each query binds its parameters by name ({@code :name} placeholders), and {@code ActionLogQueriesTest} pins every
 * constant's parameter surface (via {@code SqlParameters}) to the exact set the corresponding {@link ActionLog} method binds. So a mistyped or
 * orphaned placeholder fails at unit speed rather than only surfacing when the query is first executed against the database.
 */
final class ActionLogQueries {

    /**
     * JPQL aggregating the given actions' log entries into one {@link MonthlyActionTotal} per {@code (action, calendar-month)} within the inclusive
     * {@code [:from, :to]} window, summing the daily {@code count} - the twelve bars of the frequency chart's YEAR window.
     *
     * <p>
     * Bounded to the window it draws. It used to roll up the subject's WHOLE history and the caller then kept the anchor year's twelve months, which
     * at 50 actions x 10 years meant reading 182,600 rows to use 400 of them: measured 65 ms against 4.8 ms for the bounded form. The Stats page,
     * which was the other caller, no longer runs this at all - a month's total is the sum of its days' totals, so {@code StatsService.assemble}
     * derives it from the daily rollup it has already read rather than aggregating the same history twice.
     */
    static final String MONTHLY_TOTALS_JPQL = """
            SELECT new net.zodac.diurnal.log.MonthlyActionTotal(l.actionId, YEAR(l.logDate), MONTH(l.logDate), SUM(l.count))
            FROM ActionLog l
            WHERE l.userId = :userId AND l.actionId IN (:actionIds) AND l.logDate >= :from AND l.logDate <= :to
            GROUP BY l.actionId, YEAR(l.logDate), MONTH(l.logDate)""";

    /**
     * JPQL aggregating the given actions' log entries into a {@link DailyActionTotal} per {@code (action, logged-day)} within the inclusive
     * {@code [:from, :to]} window — the database-side daily rollup behind the Stats page's month frequency chart. A {@code (user, action, day)} entry
     * is unique, so the {@code SUM} collapses a single row per group; it is written as an aggregate so the projection stays a plain {@code long}
     * regardless.
     */
    static final String DAILY_TOTALS_JPQL = """
            SELECT new net.zodac.diurnal.log.DailyActionTotal(l.actionId, l.logDate, SUM(l.count))
            FROM ActionLog l
            WHERE l.userId = :userId AND l.actionId IN (:actionIds) AND l.logDate >= :from AND l.logDate <= :to
            GROUP BY l.actionId, l.logDate
            ORDER BY l.actionId, l.logDate""";

    /**
     * JPQL selecting which of a user's actions have ever been logged - the eligibility set behind the frequency chart's compare picker. A
     * single-column scalar read, so it needs no projection record.
     *
     * <p>
     * Asked from the ACTIONS side, as "which of these has at least one log", rather than as a {@code SELECT DISTINCT l.actionId} over the logs. The
     * two return the same set, but the distinct form has to read every log row the user owns to find the handful of values in it: at 50 actions x 10
     * years that was 182,600 index entries scanned to answer 50, on every Stats page view. The {@code EXISTS} form probes once per action and stops
     * at the first hit, so its cost is the action count and does not grow with history at all - 23.2 ms against 0.25 ms at that size.
     *
     * <p>
     * The two cannot diverge: {@code action_logs.action_id} is {@code ON DELETE CASCADE}, so a log whose action is gone cannot exist, and both
     * callers intersect the result with the user's own actions regardless.
     */
    static final String LOGGED_ACTION_IDS_JPQL = """
            SELECT a.id
            FROM Action a
            WHERE a.userId = :userId
              AND EXISTS (SELECT 1 FROM ActionLog l WHERE l.userId = :userId AND l.actionId = a.id)""";

    /**
     * The same {@link DailyActionTotal} rollup as {@link #DAILY_TOTALS_JPQL} over the given actions' <strong>whole</strong> history — the minimal
     * data the Stats page needs to compute streaks, gaps and the days-with-multiples figures, which are measured over everything ever logged and so
     * have no {@code [:from, :to]} to pin them to. Written as its own query rather than calling the ranged one with sentinel dates, so no bound has
     * to be invented that a real {@code log_date} could one day sit outside.
     */
    static final String ALL_DAILY_TOTALS_JPQL = """
            SELECT new net.zodac.diurnal.log.DailyActionTotal(l.actionId, l.logDate, SUM(l.count))
            FROM ActionLog l
            WHERE l.userId = :userId AND l.actionId IN (:actionIds)
            GROUP BY l.actionId, l.logDate
            ORDER BY l.actionId, l.logDate""";

    /**
     * JPQL reading the user's log entries within the inclusive {@code [:from, :to]} window as {@link DatedActionCount} projections - the three
     * columns the calendar feeds, the dashboard's month back-fill and the day-panel rollup actually read, rather than the whole entity.
     *
     * <p>
     * Every caller of this window immediately reduces the rows to {@code (day, action, count)}, so hydrating an {@link ActionLog} per row cost a
     * managed entity, its persistence-context entry and four unread columns for each of them - a three-month dashboard warm-up is ~2,700 rows. The
     * projection reads the same rows through the same index and builds three-field records instead. There is deliberately no {@code ORDER BY}: none
     * of the callers depends on the order, and adding one would force a sort the current plan does not pay for.
     */
    static final String RANGE_COUNTS_JPQL = """
            SELECT new net.zodac.diurnal.log.DatedActionCount(l.logDate, l.actionId, l.count)
            FROM ActionLog l
            WHERE l.userId = :userId AND l.logDate >= :from AND l.logDate <= :to""";

    /**
     * JPQL producing a cheap change-signature for a user's log entries within the inclusive {@code [:from, :to]} date range: the row {@code COUNT}
     * paired with the latest {@code updated_at}, projected into a typed {@link net.zodac.diurnal.http.ChangeSignature} (never a positional
     * {@code Object[]}). The pair changes on any insert, update or delete in the range — a delete lowers the count even when it does not move the
     * maximum — so it is a sound weak-ETag validator that never has to read the entries themselves.
     */
    static final String RANGE_VERSION_JPQL = """
            SELECT new net.zodac.diurnal.http.ChangeSignature(COUNT(l), MAX(l.updatedAt))
            FROM ActionLog l
            WHERE l.userId = :userId AND l.logDate >= :from AND l.logDate <= :to""";

    /**
     * Native upsert atomically adding {@code :delta} to a day's count: it inserts a new row (seeded at {@code LEAST(:delta, :max)}) or, on the
     * {@code action_logs_pkey} conflict, adds the capped delta to the existing count — the whole read-modify-write in one statement, so two rapid
     * taps cannot race into a unique-constraint violation. The count is capped at {@code :max} on both the insert and the update.
     */
    static final String INCREMENT_UPSERT_SQL = """
            INSERT INTO action_logs (user_id, action_id, log_date, count, created_at, updated_at)
            VALUES (:userId, :actionId, :date, LEAST(:delta, :max), :now, :now)
            ON CONFLICT ON CONSTRAINT action_logs_pkey
            DO UPDATE SET count = LEAST(action_logs.count + LEAST(:delta, :max), :max), updated_at = :now""";

    /**
     * Native SQL for the earliest day any of the given actions was logged - the bound on how far back the frequency chart may be navigated.
     *
     * <p>
     * Native, and shaped as a {@code LATERAL} per action, because the obvious spelling is a trap. A plain
     * {@code SELECT MIN(log_date) ... WHERE action_id IN (...)} cannot use the index to answer the aggregate: PostgreSQL will not push the
     * {@code MIN} into each branch of the nested loop, so it reads every row the actions own - measured 32.3 ms at 182,600 rows. Asked once per
     * action, each branch becomes PostgreSQL's {@code MIN}/{@code MAX} index optimisation - an {@code Index Only Scan} taking the FIRST entry of
     * that action's range in {@code action_logs_pkey} and stopping - so the whole statement is one index probe per action: 0.33 ms for 50 of them,
     * and a chart may hold at most {@code FrequencyCharts.MAX_SERIES} subjects. The cost is the number of actions charted and does not grow with
     * history at all, which is the property the previous approach lacked.
     */
    static final String EARLIEST_LOGGED_DATE_SQL = """
            SELECT MIN(earliest.first_logged)
            FROM actions a
            CROSS JOIN LATERAL (
                SELECT MIN(l.log_date) AS first_logged
                FROM action_logs l
                WHERE l.user_id = :userId AND l.action_id = a.id
            ) AS earliest
            WHERE a.user_id = :userId AND a.id IN (:actionIds)""";

    /**
     * Native SQL reading the current count for a single {@code (user, action, day)} entry — used to return the resulting value after an increment
     * upsert.
     */
    static final String SELECT_COUNT_SQL = """
            SELECT count
            FROM action_logs
            WHERE user_id = :userId AND action_id = :actionId AND log_date = :date""";

    /**
     * Native upsert atomically setting a day's count to an exact {@code :count}: it inserts a new row or, on the {@code action_logs_pkey} conflict,
     * overwrites the existing count with the supplied value — so a concurrent set on a not-yet-logged action cannot race into a unique-constraint
     * violation.
     */
    static final String SET_COUNT_UPSERT_SQL = """
            INSERT INTO action_logs (user_id, action_id, log_date, count, created_at, updated_at)
            VALUES (:userId, :actionId, :date, :count, :now, :now)
            ON CONFLICT ON CONSTRAINT action_logs_pkey
            DO UPDATE SET count = EXCLUDED.count, updated_at = :now""";

    /**
     * Native upsert writing MANY days' counts for one user in a single statement - the bulk arm of {@link #SET_COUNT_UPSERT_SQL}, used by the data
     * import, which replaces a whole account's history at once.
     *
     * <p>
     * The rows arrive as three parallel arrays rather than as a generated {@code VALUES} list, which is what keeps the statement's text - and so its
     * {@code :named}-parameter set - FIXED no matter how many rows are being written. A generated list would have to build placeholders per row,
     * putting the parameter names beyond both the typed {@link QueryParameter} tokens and {@code ActionLogQueriesTest}. {@code unnest} zips the three
     * into rows, and an empty set of arrays is a clean no-op rather than a malformed statement.
     *
     * <p>
     * A 3-year archive is ~33,000 entries, which as one statement per row was ~33,000 round trips; measured on a real connection at that size,
     * 3,628 ms became 812 ms. The {@code ON CONFLICT} arm is the same last-write-wins rule the single-row form uses, and cannot be reached twice
     * for one key here because {@code ImportParser} has already refused the archive over {@code DuplicateLog}.
     */
    static final String SET_COUNTS_BULK_SQL = """
            INSERT INTO action_logs (user_id, action_id, log_date, count, created_at, updated_at)
            SELECT :userId, entry.action_id, entry.log_date, entry.count, :now, :now
            FROM unnest(CAST(:actionIdArray AS UUID[]), CAST(:dateArray AS DATE[]), CAST(:countArray AS SMALLINT[]))
                AS entry(action_id, log_date, count)
            ON CONFLICT ON CONSTRAINT action_logs_pkey
            DO UPDATE SET count = EXCLUDED.count, updated_at = :now""";

    /**
     * Native SQL reading a single {@code (user, action, day)} entry's count while taking a {@code FOR UPDATE} row lock, so a decrement can make its
     * update-or-delete decision without a concurrent increment slipping in and changing the count underneath it.
     */
    static final String SELECT_FOR_UPDATE_SQL = """
            SELECT count
            FROM action_logs
            WHERE user_id = :userId AND action_id = :actionId AND log_date = :date
            FOR UPDATE""";

    /**
     * Native SQL deleting a single {@code (user, action, day)} entry — used by a decrement when the count would reach zero or below.
     */
    static final String DELETE_ENTRY_SQL = """
            DELETE FROM action_logs
            WHERE user_id = :userId AND action_id = :actionId AND log_date = :date""";

    /**
     * Native SQL writing the reduced {@code :newCount} back to a single {@code (user, action, day)} entry — the update arm of a decrement that leaves
     * the row in place because the resulting count is still positive.
     */
    static final String DECREMENT_UPDATE_SQL = """
            UPDATE action_logs
            SET count = :newCount, updated_at = :now
            WHERE user_id = :userId AND action_id = :actionId AND log_date = :date""";

    // The named parameters the queries above declare, as typed tokens: every binding goes through one of these rather than a bare string, so a
    // misspelled name - or a value of the wrong type for it - is a compile error instead of a failure on first execution. The placeholders inside
    // the query text stay textual (no Java type can reach them), which is what ActionLogQueriesTest is for.
    static final QueryParameter<UUID> USER_ID = QueryParameter.of("userId");
    static final QueryParameter<UUID> ACTION_ID = QueryParameter.of("actionId");
    static final QueryParameter<Collection<UUID>> ACTION_IDS = QueryParameter.of("actionIds");
    static final QueryParameter<LocalDate> DATE = QueryParameter.of("date");
    static final QueryParameter<LocalDate> FROM = QueryParameter.of("from");
    static final QueryParameter<LocalDate> TO = QueryParameter.of("to");
    static final QueryParameter<Integer> COUNT = QueryParameter.of("count");
    static final QueryParameter<Integer> NEW_COUNT = QueryParameter.of("newCount");
    static final QueryParameter<Integer> DELTA = QueryParameter.of("delta");
    static final QueryParameter<Integer> MAX = QueryParameter.of("max");
    static final QueryParameter<Instant> NOW = QueryParameter.of("now");

    // The bulk upsert's three parallel arrays. Typed as Java arrays rather than as a Collection because they are bound straight through to the
    // PostgreSQL array types the statement casts them to, one JDBC array each, and a Collection would bind as an IN-list instead.
    static final QueryParameter<UUID[]> ACTION_ID_ARRAY = QueryParameter.of("actionIdArray");
    static final QueryParameter<LocalDate[]> DATE_ARRAY = QueryParameter.of("dateArray");
    static final QueryParameter<Short[]> COUNT_ARRAY = QueryParameter.of("countArray");

    private ActionLogQueries() {

    }
}
