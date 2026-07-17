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
 * Unit tests for {@link SqlParameters#names(String)}, the {@code :name}-placeholder extractor backing {@link ActionLogQueriesTest}.
 */
class SqlParametersTest {

    @Test
    void names_extractsEachDistinctPlaceholder() {
        assertThat(SqlParameters.names("SELECT * FROM t WHERE a = :userId AND b <= :to"))
            .as("both named parameters should be extracted, without their colons")
            .containsExactly("userId", "to");
    }

    @Test
    void names_deduplicatesRepeatedPlaceholderKeepingFirstOrder() {
        assertThat(SqlParameters.names("VALUES (:now) ON CONFLICT DO UPDATE SET x = :delta, y = :now"))
            .as("a placeholder used twice is reported once, in first-appearance order")
            .containsExactly("now", "delta");
    }

    @Test
    void names_returnsEmptyWhenNoPlaceholders() {
        assertThat(SqlParameters.names("DELETE FROM action_logs WHERE count = 0"))
            .as("a query with no named parameters yields no names")
            .isEmpty();
    }

    @Test
    void names_ignoresPostgresDoubleColonCast() {
        assertThat(SqlParameters.names("SELECT :userId, created_at::date FROM action_logs"))
            .as("a :: type cast must not be mistaken for a named parameter")
            .containsExactly("userId");
    }

    @Test
    void names_allowsDigitsAndUnderscoresButNotLeadingDigit() {
        assertThat(SqlParameters.names("SET x = :new_count_2 WHERE y = :3bad"))
            .as("names may contain digits/underscores after a leading letter; a colon before a digit is not a parameter")
            .containsExactlyElementsOf(List.of("new_count_2"));
    }
}
