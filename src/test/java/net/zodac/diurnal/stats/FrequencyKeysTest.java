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

import java.time.LocalDate;
import net.zodac.diurnal.user.Language;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FrequencyKeysTest {

    // ── isValid ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"2026-07", "1999-01", "2026-12", "0001-06"})
    void isValid_monthKey_isAccepted(final String key) {
        assertThat(FrequencyKeys.isValid(FrequencyPeriod.MONTH, key))
            .as("a well-formed yyyy-MM key should be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026", "2026-00", "2026-13", "2026-7", "26-07", "2026-07-03", "yyyy-MM", "2026_07", " 2026-07"})
    void isValid_malformedMonthKey_isRejected(final String key) {
        assertThat(FrequencyKeys.isValid(FrequencyPeriod.MONTH, key))
            .as("a malformed month key should be rejected, never coerced to the current window")
            .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026", "1999", "0001"})
    void isValid_yearKey_isAccepted(final String key) {
        assertThat(FrequencyKeys.isValid(FrequencyPeriod.YEAR, key))
            .as("a well-formed yyyy key should be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026-07", "26", "20267", "yyyy", " 2026"})
    void isValid_malformedYearKey_isRejected(final String key) {
        assertThat(FrequencyKeys.isValid(FrequencyPeriod.YEAR, key))
            .as("a malformed year key should be rejected, never coerced to the current window")
            .isFalse();
    }

    // ── anchor / anchorOf / key ─────────────────────────────────────────────

    @Test
    void anchor_monthKey_isTheFirstOfThatMonth() {
        assertThat(FrequencyKeys.anchor(FrequencyPeriod.MONTH, "2026-07"))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void anchor_yearKey_isTheFirstOfThatJanuary() {
        assertThat(FrequencyKeys.anchor(FrequencyPeriod.YEAR, "2026"))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void anchorOf_month_isTheFirstOfTheContainingMonth() {
        assertThat(FrequencyKeys.anchorOf(FrequencyPeriod.MONTH, LocalDate.of(2026, 7, 23)))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void anchorOf_year_isTheFirstOfTheContainingJanuary() {
        assertThat(FrequencyKeys.anchorOf(FrequencyPeriod.YEAR, LocalDate.of(2026, 7, 23)))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void key_roundTripsAnAnchoredMonth() {
        assertThat(FrequencyKeys.key(FrequencyPeriod.MONTH, LocalDate.of(2026, 7, 1)))
            .as("unexpected value")
            .isEqualTo("2026-07");
    }

    @Test
    void key_roundTripsAnAnchoredYear() {
        assertThat(FrequencyKeys.key(FrequencyPeriod.YEAR, LocalDate.of(2026, 1, 1)))
            .as("unexpected value")
            .isEqualTo("2026");
    }

    // ── label ───────────────────────────────────────────────────────────────

    @Test
    void label_month_spellsTheMonthOutInFull() {
        assertThat(FrequencyKeys.label(FrequencyPeriod.MONTH, LocalDate.of(2026, 7, 1), Language.ENGLISH_GB))
            .as("unexpected value")
            .isEqualTo("July 2026");
    }

    @Test
    void label_year_isTheYearAlone() {
        assertThat(FrequencyKeys.label(FrequencyPeriod.YEAR, LocalDate.of(2026, 1, 1), Language.ENGLISH_GB))
            .as("unexpected value")
            .isEqualTo("2026");
    }

    // ── end / shift ─────────────────────────────────────────────────────────

    @Test
    void end_month_isTheLastDayOfThatMonth() {
        assertThat(FrequencyKeys.end(FrequencyPeriod.MONTH, LocalDate.of(2026, 2, 1)))
            .as("a non-leap February should end on the 28th")
            .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void end_leapFebruary_keepsTheLeapDay() {
        assertThat(FrequencyKeys.end(FrequencyPeriod.MONTH, LocalDate.of(2024, 2, 1)))
            .as("a leap February should end on the 29th")
            .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void end_year_isTheLastDayOfThatYear() {
        assertThat(FrequencyKeys.end(FrequencyPeriod.YEAR, LocalDate.of(2026, 1, 1)))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void shift_month_movesByWholeMonths() {
        assertThat(FrequencyKeys.shift(FrequencyPeriod.MONTH, LocalDate.of(2026, 1, 1), -1))
            .as("stepping back from January should land in the previous December")
            .isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(FrequencyKeys.shift(FrequencyPeriod.MONTH, LocalDate.of(2026, 1, 1), 1))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 2, 1));
    }

    @Test
    void shift_year_movesByWholeYears() {
        assertThat(FrequencyKeys.shift(FrequencyPeriod.YEAR, LocalDate.of(2026, 1, 1), -1))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(FrequencyKeys.shift(FrequencyPeriod.YEAR, LocalDate.of(2026, 1, 1), 1))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2027, 1, 1));
    }
}
