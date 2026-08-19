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

    // ── breakdown: plain days ────────────────────────────────────────────────

    @Test
    void breakdown_emptySpan_isAllZero() {
        final LocalDate date = LocalDate.of(2026, 2, 16);
        assertThat(Durations.breakdown(new DaySpan(date, date)))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 0L, 0L));
    }

    @Test
    void breakdown_oneDay_isDaysOnly() {
        assertThat(Durations.breakdown(run(LocalDate.of(2026, 2, 16), 1)))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 0L, 1L));
    }

    @Test
    void breakdown_severalDays_staysInDays() {
        assertThat(Durations.breakdown(run(LocalDate.of(2026, 2, 16), 5)))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 0L, 5L));
    }

    @Test
    void breakdown_reversedSpan_isAllZero() {
        assertThat(Durations.breakdown(between("2026-02-16", "2026-02-11")))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 0L, 0L));
    }

    // ── breakdown: months ────────────────────────────────────────────────────

    @Test
    void breakdown_exactlyOneCalendarMonth_isMonthsOnly() {
        // 16 January -> 16 February is 31 days, and measures as exactly one month: the month is counted because
        // the day-of-month was reached, and the zero day component is not dropped by the measurement itself -
        // only by whichever renderer chooses to omit a zero component.
        assertThat(Durations.breakdown(between("2026-01-16", "2026-02-16")))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 1L, 0L));
    }

    @Test
    void breakdown_oneDayShortOfOneMonth_staysInDays() {
        // 17 January -> 16 February: the day-of-month has NOT been reached, so it is still a day count.
        assertThat(Durations.breakdown(between("2026-01-17", "2026-02-16")))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 0L, 30L));
    }

    @Test
    void breakdown_twoMonthsAndOneDay_carriesBothComponents() {
        // 16 February -> 17 April, the worked example from the spec.
        assertThat(Durations.breakdown(between("2026-02-16", "2026-04-17")))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 2L, 1L));
    }

    @Test
    void breakdown_sameDayCountDifferentMonths_measuresDifferently() {
        // THE reason a duration is measured from real dates rather than a day count: 31 days is a whole month
        // in one place in the calendar and a month-and-change in another. Both spans below are 31 days.
        final DaySpan january = between("2026-01-16", "2026-02-16");
        final DaySpan february = between("2026-02-27", "2026-03-30");
        assertThat(Durations.days(january))
            .as("both spans must be the same length for this comparison to mean anything")
            .isEqualTo(Durations.days(february));
        assertThat(Durations.breakdown(january))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 1L, 0L));
        assertThat(Durations.breakdown(february))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 1L, 3L));
    }

    // ── breakdown: years ─────────────────────────────────────────────────────

    @Test
    void breakdown_exactlyOneYear_isYearsOnly() {
        assertThat(Durations.breakdown(between("2025-02-16", "2026-02-16")))
            .as("unexpected value")
            .isEqualTo(new DurationParts(1L, 0L, 0L));
    }

    @Test
    void breakdown_yearsMonthsAndDays_carriesEveryComponent() {
        assertThat(Durations.breakdown(between("2024-01-14", "2026-04-17")))
            .as("unexpected value")
            .isEqualTo(new DurationParts(2L, 3L, 3L));
    }

    @Test
    void breakdown_yearsAndDaysButNoWholeMonth_hasZeroMonths() {
        assertThat(Durations.breakdown(between("2025-02-14", "2026-02-16")))
            .as("unexpected value")
            .isEqualTo(new DurationParts(1L, 0L, 2L));
    }

    @Test
    void breakdown_leapDayIsCounted_notDroppedByFixed365DayYear() {
        // 2024 is a leap year, so this span covers 366 days and still measures as exactly one year; measuring in
        // fixed 365-day blocks would have reported "1 year, 1 day".
        final DaySpan span = between("2024-02-16", "2025-02-16");
        assertThat(Durations.days(span))
            .as("the span really does cover a leap day")
            .isEqualTo(366);
        assertThat(Durations.breakdown(span))
            .as("unexpected value")
            .isEqualTo(new DurationParts(1L, 0L, 0L));
    }
}
