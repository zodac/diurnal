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

package net.zodac.diurnal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link QueryParameter}: the name it carries into a binding, and the blank name it refuses.
 */
class QueryParameterTest {

    @Test
    void of_returnsTheNameWithoutItsColon() {
        assertThat(QueryParameter.of("userId").name())
            .as("the name is bound as-is; the leading colon belongs to the query text, not to the parameter")
            .isEqualTo("userId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void of_blankName_isRejected(final String name) {
        assertThatThrownBy(() -> QueryParameter.of(name))
            .as("a blank name could never match a placeholder, so it is refused where it is declared")
            .isInstanceOf(IllegalArgumentException.class);
    }
}
