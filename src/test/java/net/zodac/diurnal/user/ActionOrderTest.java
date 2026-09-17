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

package net.zodac.diurnal.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class ActionOrderTest {

    @ParameterizedTest
    @CsvSource({
        "ALPHABETICAL, alphabetical",
        "MOST_LOGGED, mostLogged",
        "MOST_RECENT, mostRecent"})
    void constant_hasExpectedValue(final ActionOrder order, final String value) {
        assertThat(order.value())
            .as("unexpected value")
            .isEqualTo(value);
    }

    @Test
    void default_isAlphabetical() {
        assertThat(ActionOrder.DEFAULT)
            .as("the default must stay the order the panel had before the setting existed, or an upgrade silently reorders every dashboard")
            .isEqualTo(ActionOrder.ALPHABETICAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alphabetical", "mostLogged", "mostRecent"})
    void of_resolvesEveryOfferedValue(final String value) {
        assertThat(ActionOrder.of(value).value())
            .as("an offered value must resolve to its own constant")
            .isEqualTo(value);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "ALPHABETICAL", "most-logged", "mostlogged", "nonsense"})
    void of_fallsBackToTheDefaultForAnythingElse(final String value) {
        assertThat(ActionOrder.of(value))
            .as("a value only a hand-edited row can hold must render as the default rather than fail the dashboard")
            .isEqualTo(ActionOrder.DEFAULT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"alphabetical", "mostLogged", "mostRecent"})
    void isValid_acceptsEveryOfferedValue(final String value) {
        assertThat(ActionOrder.isValid(value))
            .as("an offered value must be accepted by a settings save")
            .isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "ALPHABETICAL", "most-logged", "mostlogged", "nonsense"})
    void isValid_rejectsAnythingElse(final String value) {
        assertThat(ActionOrder.isValid(value))
            .as("a write path rejects rather than coerces, unlike of(...) which is a read")
            .isFalse();
    }

    @Test
    void allowedValues_namesEveryOfferedValueInDeclarationOrder() {
        assertThat(ActionOrder.allowedValues())
            .as("the rejection sentence must name exactly the catalogue isValid judges against")
            .isEqualTo("alphabetical, mostLogged, mostRecent");
    }
}
