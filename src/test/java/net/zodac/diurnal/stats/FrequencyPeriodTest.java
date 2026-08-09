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

package net.zodac.diurnal.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FrequencyPeriodTest {

    @Test
    void values_areTheTwoOfferedWindowsInToggleOrder() {
        final List<String> expected = List.of(
            "month",
            "year");
        assertThat(Stream.of(FrequencyPeriod.values()).map(FrequencyPeriod::value).toList())
            .as("the toggle should offer exactly month then year")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void defaultPeriod_isMonth() {
        assertThat(FrequencyPeriod.DEFAULT)
            .as("the chart should open on a month window")
            .isEqualTo(FrequencyPeriod.MONTH);
    }

    @Test
    void label_isTheToggleCaption() {
        assertThat(FrequencyPeriod.MONTH.label())
            .as("unexpected value")
            .isEqualTo("Month");
        assertThat(FrequencyPeriod.YEAR.label())
            .as("unexpected value")
            .isEqualTo("Year");
    }

    @ParameterizedTest
    @ValueSource(strings = {"month", "year"})
    void isValid_offeredValue_isAccepted(final String value) {
        assertThat(FrequencyPeriod.isValid(value))
            .as("an offered period should be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"MONTH", "Year", "week", "day", " month"})
    void isValid_unrecognisedValue_isRejected(final String value) {
        assertThat(FrequencyPeriod.isValid(value))
            .as("an unrecognised period should be rejected, never coerced")
            .isFalse();
    }

    @Test
    void of_offeredValue_returnsPeriod() {
        assertThat(FrequencyPeriod.of("year"))
            .as("unexpected value")
            .isEqualTo(FrequencyPeriod.YEAR);
    }

    @Test
    void of_unrecognisedValue_throws() {
        assertThatThrownBy(() -> FrequencyPeriod.of("week"))
            .as("an unrecognised period should not resolve to a window")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("week");
    }
}
