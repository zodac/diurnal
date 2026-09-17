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

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 23);
    private static final LocalDate EARLIEST = LocalDate.of(2024, 3, 20);

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

    @Test
    void isValid_allTimeKey_isAcceptedOnlyAsItsOwnFixedValue() {
        assertThat(FrequencyKeys.isValid(FrequencyPeriod.ALL, "all"))
            .as("the all-time window's key is the fixed value the chart posts back")
            .isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2026", "2026-07", "ALL", "all-time", " all"})
    void isValid_malformedAllTimeKey_isRejected(final String key) {
        assertThat(FrequencyKeys.isValid(FrequencyPeriod.ALL, key))
            .as("a key the all-time period does not name should be rejected, never coerced to its own window")
            .isFalse();
    }

    // ── anchor / anchorOf / key ─────────────────────────────────────────────

    @Test
    void anchor_monthKey_isTheFirstOfThatMonth() {
        assertThat(FrequencyKeys.anchor(FrequencyPeriod.MONTH, "2026-07", TODAY, EARLIEST))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void anchor_yearKey_isTheFirstOfThatJanuary() {
        assertThat(FrequencyKeys.anchor(FrequencyPeriod.YEAR, "2026", TODAY, EARLIEST))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void anchor_allTimeKey_startsAtTheEarliestLoggedYear() {
        assertThat(FrequencyKeys.anchor(FrequencyPeriod.ALL, "all", TODAY, EARLIEST))
            .as("the all-time window is anchored by the data rather than by its key")
            .isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    void defaultAnchor_datedPeriods_areTheWindowContainingToday() {
        assertThat(FrequencyKeys.defaultAnchor(FrequencyPeriod.MONTH, TODAY, EARLIEST))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(FrequencyKeys.defaultAnchor(FrequencyPeriod.YEAR, TODAY, EARLIEST))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void defaultAnchor_allTime_startsAtTheEarliestLoggedYear() {
        assertThat(FrequencyKeys.defaultAnchor(FrequencyPeriod.ALL, TODAY, EARLIEST))
            .as("asking for the all-time window by name and not asking at all must land on the same window")
            .isEqualTo(LocalDate.of(2024, 1, 1));
    }

    @Test
    void defaultAnchor_allTimeWithNothingLogged_startsAtTheCurrentYear() {
        assertThat(FrequencyKeys.defaultAnchor(FrequencyPeriod.ALL, TODAY, null))
            .as("an account with nothing logged still draws its own year")
            .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void defaultAnchor_allTimeWithOnlyFutureEntries_startsAtTheCurrentYear() {
        // A note may be written for a date that has not arrived, and no window of any period draws the future -
        // so the earliest entry can sit AFTER today, which would otherwise anchor the window past its own end.
        assertThat(FrequencyKeys.defaultAnchor(FrequencyPeriod.ALL, TODAY, LocalDate.of(2027, 2, 3)))
            .as("an entry dated after today cannot start a window the chart draws")
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
    void anchorOf_allTime_isTheFirstOfTheContainingJanuary() {
        assertThat(FrequencyKeys.anchorOf(FrequencyPeriod.ALL, LocalDate.of(2026, 7, 23)))
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

    @Test
    void key_allTimeWindow_isItsOwnFixedValue() {
        assertThat(FrequencyKeys.key(FrequencyPeriod.ALL, LocalDate.of(2024, 1, 1)))
            .as("the all-time window names no calendar unit, so its key cannot carry one")
            .isEqualTo("all");
    }

    // ── label ───────────────────────────────────────────────────────────────

    @Test
    void label_month_spellsTheMonthOutInFull() {
        assertThat(FrequencyKeys.label(FrequencyPeriod.MONTH, LocalDate.of(2026, 7, 1), TODAY, Language.ENGLISH_GB))
            .as("unexpected value")
            .isEqualTo("July 2026");
    }

    @Test
    void label_year_isTheYearAlone() {
        assertThat(FrequencyKeys.label(FrequencyPeriod.YEAR, LocalDate.of(2026, 1, 1), TODAY, Language.ENGLISH_GB))
            .as("unexpected value")
            .isEqualTo("2026");
    }

    @Test
    void label_allTime_spansTheYearsItDraws() {
        assertThat(FrequencyKeys.label(FrequencyPeriod.ALL, LocalDate.of(2024, 1, 1), TODAY, Language.ENGLISH_GB))
            .as("the all-time heading should name both ends of the span it draws")
            .isEqualTo("2024 – 2026");
    }

    @Test
    void label_allTimeWithinOneYear_isThatYearAlone() {
        assertThat(FrequencyKeys.label(FrequencyPeriod.ALL, LocalDate.of(2026, 1, 1), TODAY, Language.ENGLISH_GB))
            .as("a single-year span should read as the year, not as a range with the same year twice")
            .isEqualTo("2026");
    }

    @Test
    void label_allTime_takesTheLanguagesOwnRangeSeparator() {
        assertThat(FrequencyKeys.label(FrequencyPeriod.ALL, LocalDate.of(2024, 1, 1), TODAY, Language.JAPANESE))
            .as("Japanese writes a range with a wave dash, not an en dash")
            .isEqualTo("2024〜2026");
    }

    @Test
    void yearLabel_takesTheLanguagesOwnDigits() {
        assertThat(FrequencyKeys.yearLabel(LocalDate.of(2026, 1, 1), Language.ARABIC))
            .as("a year has no words to translate, but its digits still need this language's own glyphs")
            .isEqualTo("٢٠٢٦");
    }

    // ── end / shift ─────────────────────────────────────────────────────────

    @Test
    void end_month_isTheLastDayOfThatMonth() {
        assertThat(FrequencyKeys.end(FrequencyPeriod.MONTH, LocalDate.of(2026, 2, 1), TODAY))
            .as("a non-leap February should end on the 28th")
            .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void end_leapFebruary_keepsTheLeapDay() {
        assertThat(FrequencyKeys.end(FrequencyPeriod.MONTH, LocalDate.of(2024, 2, 1), TODAY))
            .as("a leap February should end on the 29th")
            .isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void end_year_isTheLastDayOfThatYear() {
        assertThat(FrequencyKeys.end(FrequencyPeriod.YEAR, LocalDate.of(2026, 1, 1), TODAY))
            .as("unexpected value")
            .isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void end_allTime_isTheLastDayOfTheCurrentYear() {
        assertThat(FrequencyKeys.end(FrequencyPeriod.ALL, LocalDate.of(2024, 1, 1), TODAY))
            .as("the all-time window runs to the end of the current year, never into a future one")
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
    void shift_allTime_staysWhereItIs() {
        final LocalDate anchor = LocalDate.of(2024, 1, 1);
        assertThat(FrequencyKeys.shift(FrequencyPeriod.ALL, anchor, -1))
            .as("the all-time window has no earlier neighbour to step to")
            .isEqualTo(anchor);
        assertThat(FrequencyKeys.shift(FrequencyPeriod.ALL, anchor, 1))
            .as("the all-time window has no later neighbour to step to")
            .isEqualTo(anchor);
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
