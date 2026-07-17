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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins each of {@link ActionLogQueries}' hand-written queries to the exact {@code :named}-parameter set the corresponding {@link ActionLog} method
 * binds. Because those
 * queries are untyped SQL/JPQL text bound by name, a mistyped or orphaned placeholder is otherwise only caught when the query is first executed
 * against the database; asserting the extracted parameter surface here fails such a slip at unit speed and forces any deliberate parameter change to
 * be mirrored in both the query text and the {@code setParameter} calls.
 */
class ActionLogQueriesTest {

    @Test
    void mostRecentActiveActionIdsSql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.MOST_RECENT_ACTIVE_ACTION_IDS_SQL, List.of("userId", "from", "to", "limit"));
    }

    @Test
    void rangeVersionJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.RANGE_VERSION_JPQL, List.of("userId", "from", "to"));
    }

    @Test
    void monthlyTotalsJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.MONTHLY_TOTALS_JPQL, List.of("userId", "actionIds"));
    }

    @Test
    void distinctDatesJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.DISTINCT_DATES_JPQL, List.of("userId", "actionIds"));
    }

    @Test
    void incrementUpsertSql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.INCREMENT_UPSERT_SQL, List.of("id", "userId", "actionId", "date", "delta", "max", "now"));
    }

    @Test
    void selectCountSql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.SELECT_COUNT_SQL, List.of("userId", "actionId", "date"));
    }

    @Test
    void setCountUpsertSql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.SET_COUNT_UPSERT_SQL, List.of("id", "userId", "actionId", "date", "count", "now"));
    }

    @Test
    void selectForUpdateSql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.SELECT_FOR_UPDATE_SQL, List.of("userId", "actionId", "date"));
    }

    @Test
    void deleteEntrySql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.DELETE_ENTRY_SQL, List.of("userId", "actionId", "date"));
    }

    @Test
    void decrementUpdateSql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.DECREMENT_UPDATE_SQL, List.of("newCount", "now", "userId", "actionId", "date"));
    }

    private static void assertParameters(final String query, final List<String> expected) {
        assertThat(SqlParameters.names(query))
            .as("the query's :named-parameter set must match exactly what the method binds")
            .containsExactlyInAnyOrderElementsOf(expected);
    }
}
