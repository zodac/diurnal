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

package net.zodac.diurnal.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DurationsTest {

    // A run of `days` days starting on `start`, expressed half-open exactly as the production code builds them.
    private static DaySpan run(final LocalDate start, final int days) {
        return new DaySpan(start, start.plusDays(days));
    }

    private static DaySpan between(final String startDate, final String endDate) {
        return new DaySpan(LocalDate.parse(startDate), LocalDate.parse(endDate));
    }

    // ── days ──────────────────────────────────────────────────────────────────

    @Test
    void days_emptySpan_isZero() {
        final LocalDate date = LocalDate.of(2026, 2, 16);
        assertThat(Durations.days(new DaySpan(date, date)))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void days_oneDayRun_isOne() {
        assertThat(Durations.days(run(LocalDate.of(2026, 2, 16), 1)))
            .as("a half-open [d, d+1) run is one day")
            .isEqualTo(1);
    }

    @Test
    void days_countsWholeDaysAcrossMonths() {
        assertThat(Durations.days(between("2026-01-16", "2026-02-16")))
            .as("unexpected value")
            .isEqualTo(31);
    }

    @Test
    void days_reversedSpan_clampsToZero() {
        // Defensive: a reversed span would otherwise report a negative length.
        assertThat(Durations.days(between("2026-02-16", "2026-02-11")))
            .as("unexpected value")
            .isZero();
    }

    // ── label: plain days ─────────────────────────────────────────────────────

    @Test
    void label_emptySpan_isZeroDays() {
        final LocalDate date = LocalDate.of(2026, 2, 16);
        assertThat(Durations.label(new DaySpan(date, date)))
            .as("unexpected value")
            .isEqualTo("0 days");
    }

    @Test
    void label_oneDay_isSingular() {
        assertThat(Durations.label(run(LocalDate.of(2026, 2, 16), 1)))
            .as("a one-day span must never read '1 days'")
            .isEqualTo("1 day");
    }

    @Test
    void label_severalDays_staysInDays() {
        assertThat(Durations.label(run(LocalDate.of(2026, 2, 16), 5)))
            .as("unexpected value")
            .isEqualTo("5 days");
    }

    @Test
    void label_reversedSpan_isZeroDays() {
        assertThat(Durations.label(between("2026-02-16", "2026-02-11")))
            .as("unexpected value")
            .isEqualTo("0 days");
    }

    // ── label: months ─────────────────────────────────────────────────────────

    @Test
    void label_exactlyOneCalendarMonth_isSingularMonthWithNoDays() {
        // 16 January -> 16 February is 31 days, and reads as exactly "1 month": the month is counted because
        // the day-of-month was reached, and the zero day component is omitted.
        assertThat(Durations.label(between("2026-01-16", "2026-02-16")))
            .as("unexpected value")
            .isEqualTo("1 month");
    }

    @Test
    void label_oneDayShortOfOneMonth_staysInDays() {
        // 17 January -> 16 February: the day-of-month has NOT been reached, so it is still a day count.
        assertThat(Durations.label(between("2026-01-17", "2026-02-16")))
            .as("unexpected value")
            .isEqualTo("30 days");
    }

    @Test
    void label_twoMonthsAndOneDay_listsBothComponents() {
        // 16 February -> 17 April, the worked example from the spec.
        assertThat(Durations.label(between("2026-02-16", "2026-04-17")))
            .as("unexpected value")
            .isEqualTo("2 months, 1 day");
    }

    @Test
    void label_monthsWithNoRemainingDays_omitsTheDayComponent() {
        assertThat(Durations.label(between("2026-02-17", "2026-04-17")))
            .as("unexpected value")
            .isEqualTo("2 months");
    }

    @Test
    void label_sameDayCountDifferentMonths_readsDifferently() {
        // THE reason a duration is measured from real dates rather than a day count: 31 days is a whole month
        // in one place in the calendar and a month-and-change in another. Both spans below are 31 days.
        final DaySpan january = between("2026-01-16", "2026-02-16");
        final DaySpan february = between("2026-02-27", "2026-03-30");
        assertThat(Durations.days(january))
            .as("both spans must be the same length for this comparison to mean anything")
            .isEqualTo(Durations.days(february));
        assertThat(Durations.label(january))
            .as("unexpected value")
            .isEqualTo("1 month");
        assertThat(Durations.label(february))
            .as("unexpected value")
            .isEqualTo("1 month, 3 days");
    }

    // ── label: years ──────────────────────────────────────────────────────────

    @Test
    void label_exactlyOneYear_isSingularYearOnly() {
        assertThat(Durations.label(between("2025-02-16", "2026-02-16")))
            .as("unexpected value")
            .isEqualTo("1 year");
    }

    @Test
    void label_yearsMonthsAndDays_listsEveryComponentInOrder() {
        assertThat(Durations.label(between("2024-01-14", "2026-04-17")))
            .as("unexpected value")
            .isEqualTo("2 years, 3 months, 3 days");
    }

    @Test
    void label_yearsAndDaysButNoWholeMonth_omitsTheMonthComponent() {
        assertThat(Durations.label(between("2025-02-14", "2026-02-16")))
            .as("unexpected value")
            .isEqualTo("1 year, 2 days");
    }

    @Test
    void label_leapDayIsCounted_notDroppedByFixed365DayYear() {
        // 2024 is a leap year, so this span covers 366 days and is still exactly one year; measuring in fixed
        // 365-day blocks would have reported "1 year, 1 day".
        final DaySpan span = between("2024-02-16", "2025-02-16");
        assertThat(Durations.days(span))
            .as("the span really does cover a leap day")
            .isEqualTo(366);
        assertThat(Durations.label(span))
            .as("unexpected value")
            .isEqualTo("1 year");
    }

    // ── exceedsOneMonth ───────────────────────────────────────────────────────

    @Test
    void exceedsOneMonth_underOneMonth_isFalse() {
        assertThat(Durations.exceedsOneMonth(between("2026-01-17", "2026-02-16")))
            .as("expected condition to be false")
            .isFalse();
    }

    @Test
    void exceedsOneMonth_exactlyOneMonth_isTrue() {
        assertThat(Durations.exceedsOneMonth(between("2026-01-16", "2026-02-16")))
            .as("expected condition to be true")
            .isTrue();
    }

    @Test
    void exceedsOneMonth_emptySpan_isFalse() {
        final LocalDate date = LocalDate.of(2026, 2, 16);
        assertThat(Durations.exceedsOneMonth(new DaySpan(date, date)))
            .as("expected condition to be false")
            .isFalse();
    }

    @Test
    void exceedsOneMonth_reversedSpan_isFalse() {
        assertThat(Durations.exceedsOneMonth(between("2026-04-17", "2026-02-16")))
            .as("expected condition to be false")
            .isFalse();
    }

    // ── count / plural ────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"0,0 times", "1,1 time", "2,2 times", "999,999 times"})
    void count_rendersTheCountWithSingularAwareUnit(final long count, final String expected) {
        assertThat(Durations.count(count, "time"))
            .as("unexpected value")
            .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"0,days", "1,day", "2,days", "1000,days"})
    void plural_onlyOneIsSingular(final long count, final String expected) {
        assertThat(Durations.plural(count, "day"))
            .as("unexpected value")
            .isEqualTo(expected);
    }
}
