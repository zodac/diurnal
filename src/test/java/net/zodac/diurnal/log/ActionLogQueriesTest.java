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

import static net.zodac.diurnal.log.ActionLogQueries.ACTION_ID;
import static net.zodac.diurnal.log.ActionLogQueries.ACTION_IDS;
import static net.zodac.diurnal.log.ActionLogQueries.ACTION_ID_ARRAY;
import static net.zodac.diurnal.log.ActionLogQueries.COUNT;
import static net.zodac.diurnal.log.ActionLogQueries.COUNT_ARRAY;
import static net.zodac.diurnal.log.ActionLogQueries.DATE;
import static net.zodac.diurnal.log.ActionLogQueries.DATE_ARRAY;
import static net.zodac.diurnal.log.ActionLogQueries.DELTA;
import static net.zodac.diurnal.log.ActionLogQueries.FROM;
import static net.zodac.diurnal.log.ActionLogQueries.MAX;
import static net.zodac.diurnal.log.ActionLogQueries.NOW;
import static net.zodac.diurnal.log.ActionLogQueries.TO;
import static net.zodac.diurnal.log.ActionLogQueries.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.zodac.diurnal.SqlParameters;
import net.zodac.diurnal.persistence.QueryParameter;
import net.zodac.diurnal.persistence.postgres.PostgresLogStatements;
import org.junit.jupiter.api.Test;

/**
 * Pins each of {@link ActionLogQueries}' hand-written JPQL queries - and each native statement of the shipped
 * {@link PostgresLogStatements} - to the exact {@code :named}-parameter set the corresponding {@link ActionLog} method binds. The vendor statements
 * are covered here rather than beside their own class because the tokens they must honour are declared in {@link ActionLogQueries}: a second
 * implementation is pinned by adding its own cases below, which is what stops it drifting from the shared placeholder contract.
 *
 * <p>
 * Because those queries are untyped SQL/JPQL text, a placeholder that no {@link net.zodac.diurnal.persistence.QueryParameter} declares - or a
 * declared parameter that no query holds - is otherwise only caught when the query is first executed against the database. The bindings themselves
 * are compile-checked against those declarations, so this is the half of the pair that Java cannot see: the {@code :name} text inside each query.
 */
class ActionLogQueriesTest {

    private static final PostgresLogStatements POSTGRES = new PostgresLogStatements();

    @Test
    void rangeVersionJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.RANGE_VERSION_JPQL, List.of(USER_ID, FROM, TO));
    }

    @Test
    void monthlyTotalsJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.MONTHLY_TOTALS_JPQL, List.of(USER_ID, ACTION_IDS, FROM, TO));
    }

    @Test
    void dailyTotalsJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.DAILY_TOTALS_JPQL, List.of(USER_ID, ACTION_IDS, FROM, TO));
    }

    @Test
    void earliestLoggedDateSql_bindsExpectedParameters() {
        assertParameters(POSTGRES.earliestLoggedDate(), List.of(USER_ID, ACTION_IDS));
    }

    @Test
    void loggedActionIdsJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.LOGGED_ACTION_IDS_JPQL, List.of(USER_ID));
    }

    @Test
    void allDailyTotalsJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.ALL_DAILY_TOTALS_JPQL, List.of(USER_ID, ACTION_IDS));
    }

    @Test
    void incrementUpsertSql_bindsExpectedParameters() {
        assertParameters(POSTGRES.incrementUpsert(), List.of(USER_ID, ACTION_ID, DATE, DELTA, MAX, NOW));
    }

    @Test
    void assignCountUpsertSql_bindsExpectedParameters() {
        assertParameters(POSTGRES.assignCountUpsert(), List.of(USER_ID, ACTION_ID, DATE, COUNT, NOW));
    }

    @Test
    void assignCountsBulkSql_bindsExpectedParameters() {
        assertParameters(POSTGRES.assignCountsBulk(), List.of(USER_ID, ACTION_ID_ARRAY, DATE_ARRAY, COUNT_ARRAY, NOW));
    }

    @Test
    void rangeCountsJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.RANGE_COUNTS_JPQL, List.of(USER_ID, FROM, TO));
    }

    @Test
    void entryCountJpql_bindsExpectedParameters() {
        assertParameters(ActionLogQueries.ENTRY_COUNT_JPQL, List.of(USER_ID, ACTION_ID, DATE));
    }

    private static void assertParameters(final String query, final List<QueryParameter<?>> expected) {
        assertThat(SqlParameters.names(query))
            .as("the query's :named-parameter set must match exactly the parameters declared for it")
            .containsExactlyInAnyOrderElementsOf(expected.stream().map(QueryParameter::name).toList());
    }
}
