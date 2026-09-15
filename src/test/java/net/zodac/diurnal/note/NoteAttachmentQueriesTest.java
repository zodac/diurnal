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

import static net.zodac.diurnal.note.NoteAttachmentQueries.DATE;
import static net.zodac.diurnal.note.NoteAttachmentQueries.ID;
import static net.zodac.diurnal.note.NoteAttachmentQueries.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.zodac.diurnal.SqlParameters;
import net.zodac.diurnal.persistence.QueryParameter;
import org.junit.jupiter.api.Test;

/**
 * Pins each of {@link NoteAttachmentQueries}' hand-written JPQL queries to the exact {@code :named}-parameter set the corresponding
 * {@link NoteAttachment} finder binds — the {@link NoteQueriesTest} guard, for the same reason: the bindings are compile-checked against the declared
 * tokens, but the {@code :name} text inside the query itself is untyped, so a mistyped or orphaned placeholder would otherwise surface only on the
 * query's first execution against a database.
 */
class NoteAttachmentQueriesTest {

    @Test
    void sealedForDateJpql_bindsExpectedParameters() {
        assertParameters(NoteAttachmentQueries.SEALED_FOR_DATE_JPQL, List.of(USER_ID, DATE));
    }

    @Test
    void sealedForUserJpql_bindsExpectedParameters() {
        assertParameters(NoteAttachmentQueries.SEALED_FOR_USER_JPQL, List.of(USER_ID));
    }

    @Test
    void sealedByIdJpql_bindsExpectedParameters() {
        assertParameters(NoteAttachmentQueries.SEALED_BY_ID_JPQL, List.of(USER_ID, ID));
    }

    @Test
    void contentByIdJpql_bindsExpectedParameters() {
        assertParameters(NoteAttachmentQueries.CONTENT_BY_ID_JPQL, List.of(USER_ID, ID));
    }

    @Test
    void datesJpql_bindsExpectedParameters() {
        assertParameters(NoteAttachmentQueries.DATES_JPQL, List.of(USER_ID));
    }

    @Test
    void everyQuery_filtersOnTheOwner() {
        final List<String> queries = List.of(NoteAttachmentQueries.SEALED_FOR_DATE_JPQL, NoteAttachmentQueries.SEALED_FOR_USER_JPQL,
            NoteAttachmentQueries.SEALED_BY_ID_JPQL, NoteAttachmentQueries.CONTENT_BY_ID_JPQL, NoteAttachmentQueries.DATES_JPQL);

        assertThat(queries)
            .as("an attachment id is unguessable, but unguessable is not authorised - the owner belongs in the query rather than in a check "
                + "a caller has to remember to make")
            .allMatch(query -> SqlParameters.names(query).contains(USER_ID.name()));
    }

    private static void assertParameters(final String query, final List<QueryParameter<?>> expected) {
        assertThat(SqlParameters.names(query))
            .as("the query's named parameters must match exactly the parameters declared for it")
            .containsExactlyInAnyOrderElementsOf(expected.stream().map(QueryParameter::name).toList());
    }
}
