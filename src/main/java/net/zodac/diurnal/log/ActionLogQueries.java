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
     * JPQL aggregating the given actions' log entries into one {@link MonthlyActionTotal} per {@code (action, calendar-month)}, summing the daily
     * {@code count} — the database-side monthly rollup behind the Stats page.
     */
    static final String MONTHLY_TOTALS_JPQL = """
            SELECT new net.zodac.diurnal.log.MonthlyActionTotal(l.actionId, YEAR(l.logDate), MONTH(l.logDate), SUM(l.count))
            FROM ActionLog l
            WHERE l.userId = :userId AND l.actionId IN (:actionIds)
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
     * JPQL selecting the distinct actions a user has ever logged. A single-column scalar read, so it needs no projection record; it is the cheapest
     * way to answer "which actions are worth charting" for the frequency chart's compare picker without computing any statistics.
     */
    static final String LOGGED_ACTION_IDS_JPQL = """
            SELECT DISTINCT l.actionId
            FROM ActionLog l
            WHERE l.userId = :userId""";

    /**
     * JPQL selecting one {@link ActionPerformedDate} per {@code (action, logged-day)} for the given actions, ordered by action then date — the
     * minimal data needed to compute streaks and gaps.
     */
    static final String DISTINCT_DATES_JPQL = """
            SELECT new net.zodac.diurnal.log.ActionPerformedDate(l.actionId, l.logDate)
            FROM ActionLog l
            WHERE l.userId = :userId AND l.actionId IN (:actionIds)
            ORDER BY l.actionId, l.logDate""";

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
     * {@code action_logs_unique} conflict, adds the capped delta to the existing count — the whole read-modify-write in one statement, so two rapid
     * taps cannot race into a unique-constraint violation. The count is capped at {@code :max} on both the insert and the update.
     */
    static final String INCREMENT_UPSERT_SQL = """
            INSERT INTO action_logs (id, user_id, action_id, log_date, count, created_at, updated_at)
            VALUES (:id, :userId, :actionId, :date, LEAST(:delta, :max), :now, :now)
            ON CONFLICT ON CONSTRAINT action_logs_unique
            DO UPDATE SET count = LEAST(action_logs.count + LEAST(:delta, :max), :max), updated_at = :now""";

    /**
     * Native SQL reading the current count for a single {@code (user, action, day)} entry — used to return the resulting value after an increment
     * upsert.
     */
    static final String SELECT_COUNT_SQL = """
            SELECT count
            FROM action_logs
            WHERE user_id = :userId AND action_id = :actionId AND log_date = :date""";

    /**
     * Native upsert atomically setting a day's count to an exact {@code :count}: it inserts a new row or, on the {@code action_logs_unique} conflict,
     * overwrites the existing count with the supplied value — so a concurrent set on a not-yet-logged action cannot race into a unique-constraint
     * violation.
     */
    static final String SET_COUNT_UPSERT_SQL = """
            INSERT INTO action_logs (id, user_id, action_id, log_date, count, created_at, updated_at)
            VALUES (:id, :userId, :actionId, :date, :count, :now, :now)
            ON CONFLICT ON CONSTRAINT action_logs_unique
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
    static final QueryParameter<UUID> ID = QueryParameter.of("id");
    static final QueryParameter<LocalDate> DATE = QueryParameter.of("date");
    static final QueryParameter<LocalDate> FROM = QueryParameter.of("from");
    static final QueryParameter<LocalDate> TO = QueryParameter.of("to");
    static final QueryParameter<Integer> COUNT = QueryParameter.of("count");
    static final QueryParameter<Integer> NEW_COUNT = QueryParameter.of("newCount");
    static final QueryParameter<Integer> DELTA = QueryParameter.of("delta");
    static final QueryParameter<Integer> MAX = QueryParameter.of("max");
    static final QueryParameter<Instant> NOW = QueryParameter.of("now");

    private ActionLogQueries() {

    }
}
