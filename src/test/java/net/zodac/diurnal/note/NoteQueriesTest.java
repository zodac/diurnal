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

package net.zodac.diurnal.note;

import static net.zodac.diurnal.note.NoteQueries.CONTENT_ENCRYPTED;
import static net.zodac.diurnal.note.NoteQueries.DATE;
import static net.zodac.diurnal.note.NoteQueries.FROM;
import static net.zodac.diurnal.note.NoteQueries.ID;
import static net.zodac.diurnal.note.NoteQueries.NOW;
import static net.zodac.diurnal.note.NoteQueries.SUBJECT_ID;
import static net.zodac.diurnal.note.NoteQueries.TO;
import static net.zodac.diurnal.note.NoteQueries.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.zodac.diurnal.SqlParameters;
import net.zodac.diurnal.persistence.QueryParameter;
import org.junit.jupiter.api.Test;

/**
 * Pins each of {@link NoteQueries}' hand-written queries to the exact {@code :named}-parameter set the corresponding {@link Note} method binds.
 * Because those queries are untyped SQL/JPQL text, a placeholder that no {@link net.zodac.diurnal.persistence.QueryParameter} declares - or a
 * declared parameter that no query holds - is otherwise only caught when the query is first executed against the database. The bindings themselves
 * are compile-checked against those declarations, so this is the half of the pair that Java cannot see: the {@code :name} text inside each query.
 */
class NoteQueriesTest {

    @Test
    void rangeVersionJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.RANGE_VERSION_JPQL, List.of(USER_ID, FROM, TO));
    }

    @Test
    void allVersionJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.ALL_VERSION_JPQL, List.of(USER_ID));
    }

    @Test
    void upsertSql_bindsExpectedParameters() {
        assertParameters(NoteQueries.UPSERT_SQL, List.of(ID, USER_ID, DATE, CONTENT_ENCRYPTED, NOW));
    }

    @Test
    void monthlyTotalsJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.MONTHLY_TOTALS_JPQL, List.of(SUBJECT_ID, USER_ID));
    }

    @Test
    void allDailyTotalsJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.ALL_DAILY_TOTALS_JPQL, List.of(SUBJECT_ID, USER_ID));
    }

    @Test
    void dailyTotalsJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.DAILY_TOTALS_JPQL, List.of(SUBJECT_ID, USER_ID, FROM, TO));
    }

    @Test
    void allSealedJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.ALL_SEALED_JPQL, List.of(USER_ID));
    }

    @Test
    void allSealedAscendingJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.ALL_SEALED_ASCENDING_JPQL, List.of(USER_ID));
    }

    @Test
    void rangeSealedJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.RANGE_SEALED_JPQL, List.of(USER_ID, FROM, TO));
    }

    private static void assertParameters(final String query, final List<QueryParameter> expected) {
        assertThat(SqlParameters.names(query))
            .as("the query's named parameters must match exactly the parameters declared for it")
            .containsExactlyInAnyOrderElementsOf(expected.stream().map(QueryParameter::name).toList());
    }
}
