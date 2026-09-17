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
    void values_areTheOfferedWindowsInToggleOrder() {
        final List<String> expected = List.of(
            "month",
            "year",
            "all");
        assertThat(Stream.of(FrequencyPeriod.values()).map(FrequencyPeriod::value).toList())
            .as("the toggle should offer exactly month, then year, then all time")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void defaultPeriod_isMonth() {
        assertThat(FrequencyPeriod.DEFAULT)
            .as("the chart should open on a month window")
            .isEqualTo(FrequencyPeriod.MONTH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"month", "year", "all"})
    void isValid_offeredValue_isAccepted(final String value) {
        assertThat(FrequencyPeriod.isValid(value))
            .as("an offered period should be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"MONTH", "Year", "week", "day", " month", "All", "alltime"})
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
    void of_allTimeValue_returnsPeriod() {
        assertThat(FrequencyPeriod.of("all"))
            .as("unexpected value")
            .isEqualTo(FrequencyPeriod.ALL);
    }

    @Test
    void steppable_datedWindows_haveNeighbours() {
        assertThat(FrequencyPeriod.MONTH.steppable())
            .as("a month sits in a sequence of months")
            .isTrue();
        assertThat(FrequencyPeriod.YEAR.steppable())
            .as("a year sits in a sequence of years")
            .isTrue();
    }

    @Test
    void steppable_allTimeWindow_hasNone() {
        assertThat(FrequencyPeriod.ALL.steppable())
            .as("the all-time window already spans everything, so there is nothing either side of it")
            .isFalse();
    }

    @Test
    void of_unrecognisedValue_throws() {
        assertThatThrownBy(() -> FrequencyPeriod.of("week"))
            .as("an unrecognised period should not resolve to a window")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("week");
    }
}
