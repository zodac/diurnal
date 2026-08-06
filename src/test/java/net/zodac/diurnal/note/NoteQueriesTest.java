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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.zodac.diurnal.log.SqlParameters;
import org.junit.jupiter.api.Test;

/**
 * Pins each of {@link NoteQueries}' hand-written queries to the exact {@code :named}-parameter set the corresponding {@link Note} method binds.
 * Because those queries are untyped SQL/JPQL text bound by name, a mistyped or orphaned placeholder is otherwise only caught when the query is first
 * executed against the database; asserting the extracted parameter surface here fails such a slip at unit speed and forces any deliberate parameter
 * change to be mirrored in both the query text and the {@code setParameter} calls.
 */
class NoteQueriesTest {

    @Test
    void rangeVersionJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.RANGE_VERSION_JPQL, List.of("userId", "from", "to"));
    }

    @Test
    void upsertSql_bindsExpectedParameters() {
        assertParameters(NoteQueries.UPSERT_SQL, List.of("id", "userId", "date", "contentEncrypted", "now"));
    }

    @Test
    void monthlyTotalsJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.MONTHLY_TOTALS_JPQL, List.of("subjectId", "userId"));
    }

    @Test
    void noteDatesJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.NOTE_DATES_JPQL, List.of("userId"));
    }

    @Test
    void dailyTotalsJpql_bindsExpectedParameters() {
        assertParameters(NoteQueries.DAILY_TOTALS_JPQL, List.of("subjectId", "userId", "from", "to"));
    }

    private static void assertParameters(final String query, final List<String> expected) {
        assertThat(SqlParameters.names(query))
            .as("the query's named parameters must match exactly the set the Note method binds")
            .containsExactlyInAnyOrderElementsOf(expected);
    }
}
