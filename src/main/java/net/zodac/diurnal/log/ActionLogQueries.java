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

/**
 * The handwritten SQL and JPQL queries backing {@link ActionLog}'s static finder and mutation methods, held here as named constants to keep the
 * entity itself readable. Each query binds its parameters by name ({@code :name} placeholders), and {@code ActionLogQueriesTest} pins every
 * constant's parameter surface (via {@link SqlParameters}) to the exact set the corresponding {@link ActionLog} method binds. So a mistyped or
 * orphaned placeholder fails at unit speed rather than only surfacing when the query is first executed against the database.
 */
final class ActionLogQueries {

    /**
     * Native SQL selecting the ids of a user's actions logged at least once within the inclusive {@code [:from, :to]} date range, grouped per action
     * and ordered most-recently-performed first (ties broken by action name, ascending), capped at {@code :limit}. Joins {@code actions} so the
     * name-based tie-break is available.
     */
    static final String MOST_RECENT_ACTIVE_ACTION_IDS_SQL = """
            SELECT al.action_id
            FROM action_logs al
            JOIN actions a ON a.id = al.action_id
            WHERE al.user_id = :userId
              AND al.log_date >= :from AND al.log_date <= :to
            GROUP BY al.action_id, a.name
            ORDER BY MAX(al.log_date) DESC, a.name ASC
            LIMIT :limit""";

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
    static final String SELECT_COUNT_SQL =
        "SELECT count FROM action_logs WHERE user_id = :userId AND action_id = :actionId AND log_date = :date";

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
            SELECT count FROM action_logs
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

    private ActionLogQueries() {

    }
}
