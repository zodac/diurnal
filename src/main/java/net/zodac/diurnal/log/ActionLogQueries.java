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
 * The handwritten JPQL queries backing {@link ActionLog}'s static finder methods, held here as named constants to keep the entity itself readable,
 * together with the typed {@link QueryParameter} tokens every action-log query binds through. Each query binds its parameters by name
 * ({@code :name} placeholders), and {@code ActionLogQueriesTest} pins every constant's parameter surface (via {@code SqlParameters}) to the exact
 * set the corresponding {@link ActionLog} method binds. So a mistyped or orphaned placeholder fails at unit speed rather than only surfacing when
 * the query is first executed against the database.
 *
 * <p>
 * These are JPQL only, and deliberately so: Hibernate renders them for whichever dialect is configured, so they are portable as written and stay
 * beside the entity. The action-log statements that JPQL cannot express - the upserts, the bulk write and the earliest-logged probe - are
 * vendor-specific and live behind {@link net.zodac.diurnal.persistence.LogStatements} instead. The tokens below serve both, because a placeholder
 * name is part of the contract every implementation of that interface honours.
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
     * JPQL reading the current count of a single {@code (user, action, day)} entry - the read-back after an increment. The upsert that applies the
     * increment is native SQL and returns nothing a caller can use, so the resulting value is asked for separately, and asked of the database rather
     * than the persistence context because that upsert wrote straight past it. A single-column scalar read, so it needs no projection record.
     */
    static final String ENTRY_COUNT_JPQL = """
            SELECT l.count
            FROM ActionLog l
            WHERE l.userId = :userId AND l.actionId = :actionId AND l.logDate = :date""";

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

    // The named parameters the queries above and every LogStatements implementation declare, as typed tokens: every binding goes through one of
    // these rather than a bare string, so a misspelled name - or a value of the wrong type for it - is a compile error instead of a failure on first
    // execution. The placeholders inside the query text stay textual (no Java type can reach them), which is what ActionLogQueriesTest is for.
    static final QueryParameter<UUID> USER_ID = QueryParameter.of("userId");
    static final QueryParameter<UUID> ACTION_ID = QueryParameter.of("actionId");
    static final QueryParameter<Collection<UUID>> ACTION_IDS = QueryParameter.of("actionIds");
    static final QueryParameter<LocalDate> DATE = QueryParameter.of("date");
    static final QueryParameter<LocalDate> FROM = QueryParameter.of("from");
    static final QueryParameter<LocalDate> TO = QueryParameter.of("to");
    static final QueryParameter<Integer> COUNT = QueryParameter.of("count");
    static final QueryParameter<Integer> DELTA = QueryParameter.of("delta");
    static final QueryParameter<Integer> MAX = QueryParameter.of("max");
    static final QueryParameter<Instant> NOW = QueryParameter.of("now");

    // The bulk upsert's three parallel arrays. Typed as Java arrays rather than as a Collection because they are bound straight through to the
    // array types the statement casts them to, one JDBC array each, and a Collection would bind as an IN-list instead.
    static final QueryParameter<UUID[]> ACTION_ID_ARRAY = QueryParameter.of("actionIdArray");
    static final QueryParameter<LocalDate[]> DATE_ARRAY = QueryParameter.of("dateArray");
    static final QueryParameter<Short[]> COUNT_ARRAY = QueryParameter.of("countArray");

    private ActionLogQueries() {

    }
}
