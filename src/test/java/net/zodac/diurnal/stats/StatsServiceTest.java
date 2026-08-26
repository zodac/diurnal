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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.log.DailyActionTotal;
import net.zodac.diurnal.time.DaySpan;
import net.zodac.diurnal.time.DurationParts;
import net.zodac.diurnal.time.Durations;
import org.junit.jupiter.api.Test;

class StatsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    // ── currentStreak ─────────────────────────────────────────────────────────

    @Test
    void currentStreak_empty_returnsZero() {
        assertThat(Durations.days(StatsService.currentStreak(List.of(), TODAY)))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void currentStreak_todayOnly_returnsOne() {
        assertThat(Durations.days(StatsService.currentStreak(List.of(TODAY), TODAY)))
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void currentStreak_yesterdayOnly_returnsOne() {
        // Grace rule: yesterday counts when today has not been logged yet
        assertThat(Durations.days(StatsService.currentStreak(List.of(TODAY.minusDays(1)), TODAY)))
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void currentStreak_todayAndYesterday_returnsTwo() {
        assertThat(Durations.days(StatsService.currentStreak(List.of(TODAY.minusDays(1), TODAY), TODAY)))
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void currentStreak_threeDayRunEndingToday_returnsThree() {
        assertThat(Durations.days(StatsService.currentStreak(List.of(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), TODAY)))
            .as("unexpected value")
            .isEqualTo(3);
    }

    @Test
    void currentStreak_threeDayRunEndingYesterday_returnsTwo() {
        // Today not logged — grace shifts cursor to yesterday, then back two more
        assertThat(Durations.days(StatsService.currentStreak(List.of(TODAY.minusDays(2), TODAY.minusDays(1)), TODAY)))
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void currentStreak_gapBreaksRun() {
        // today-3, today-1, today — gap on today-2 breaks older portion
        assertThat(Durations.days(StatsService.currentStreak(List.of(TODAY.minusDays(3), TODAY.minusDays(1), TODAY), TODAY)))
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void currentStreak_oldHistoryOnly_returnsZero() {
        // 30 days ago is neither today nor yesterday — no current streak
        assertThat(Durations.days(StatsService.currentStreak(List.of(TODAY.minusDays(30)), TODAY)))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void currentStreak_twoOldEntriesThenGap_returnsZero() {
        assertThat(Durations.days(StatsService.currentStreak(List.of(TODAY.minusDays(5), TODAY.minusDays(4)), TODAY)))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void currentStreak_streakIsCountedInDaysNotLogCount() {
        // Even if the same date appears multiple times (shouldn't happen via dedupe upstream,
        // but the Set construction should handle it gracefully)
        final List<LocalDate> repeated = List.of(TODAY, TODAY);
        // Set dedupe means streak = 1, not 2
        assertThat(Durations.days(StatsService.currentStreak(repeated, TODAY)))
            .as("unexpected value")
            .isEqualTo(1);
    }

    // ── currentStreak: the run's dates ────────────────────────────────────────
    // The span, not just its length: the dates are what let the run be rendered as a calendar duration
    // ("1 month, 3 days") that does not change as today moves on.

    @Test
    void currentStreak_runEndingToday_spansItsFirstDayToTheDayAfterToday() {
        final List<LocalDate> dates = List.of(TODAY.minusDays(2), TODAY.minusDays(1), TODAY);
        assertThat(StatsService.currentStreak(dates, TODAY))
            .as("half-open: first day of the run, up to the day after its last")
            .isEqualTo(new DaySpan(TODAY.minusDays(2), TODAY.plusDays(1)));
    }

    @Test
    void currentStreak_runEndingYesterday_endsAtToday() {
        final List<LocalDate> dates = List.of(TODAY.minusDays(2), TODAY.minusDays(1));
        assertThat(StatsService.currentStreak(dates, TODAY))
            .as("today is not logged, so the run ends yesterday (exclusive end = today)")
            .isEqualTo(new DaySpan(TODAY.minusDays(2), TODAY));
    }

    @Test
    void currentStreak_noCurrentRun_isAnEmptySpanAtToday() {
        assertThat(StatsService.currentStreak(List.of(TODAY.minusDays(30)), TODAY))
            .as("a broken streak has no dates of its own, so it collapses to an empty span at today")
            .isEqualTo(new DaySpan(TODAY, TODAY));
    }

    // ── longestGap ────────────────────────────────────────────────────────────

    @Test
    void longestGap_empty_returnsZero() {
        assertThat(Durations.days(StatsService.longestGap(List.of(), TODAY)))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void longestGap_singleEntryToday_returnsZero() {
        assertThat(Durations.days(StatsService.longestGap(List.of(TODAY), TODAY)))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void longestGap_singleEntryYesterday_returnsOne() {
        assertThat(Durations.days(StatsService.longestGap(List.of(TODAY.minusDays(1)), TODAY)))
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void longestGap_singleEntryTenDaysAgo_returnsTen() {
        assertThat(Durations.days(StatsService.longestGap(List.of(TODAY.minusDays(10)), TODAY)))
            .as("unexpected value")
            .isEqualTo(10);
    }

    @Test
    void longestGap_twoConsecutiveEndingToday_returnsZero() {
        assertThat(Durations.days(StatsService.longestGap(List.of(TODAY.minusDays(1), TODAY), TODAY)))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void longestGap_historicalGapLargerThanOpenGap() {
        // Gap between today-10 and today-1 = 8; open gap (today-1 to today) = 1
        final List<LocalDate> dates = List.of(TODAY.minusDays(10), TODAY.minusDays(1), TODAY);
        assertThat(Durations.days(StatsService.longestGap(dates, TODAY)))
            .as("unexpected value")
            .isEqualTo(8);
    }

    @Test
    void longestGap_openGapLargerThanHistoricalGap() {
        // Gap between today-20 and today-18 = 1; open gap from today-18 to today = 18
        final List<LocalDate> dates = List.of(TODAY.minusDays(20), TODAY.minusDays(18));
        assertThat(Durations.days(StatsService.longestGap(dates, TODAY)))
            .as("unexpected value")
            .isEqualTo(18);
    }

    @Test
    void longestGap_multipleGapsTakesLargest() {
        final List<LocalDate> dates = List.of(
            TODAY.minusDays(30), TODAY.minusDays(20), TODAY.minusDays(5), TODAY
        );
        // today-30 to today-20: 10 - 1 = 9; today-20 to today-5: 15 - 1 = 14; today-5 to today: 5 - 1 = 4; open: 0
        assertThat(Durations.days(StatsService.longestGap(dates, TODAY)))
            .as("unexpected value")
            .isEqualTo(14);
    }

    @Test
    void longestGap_allConsecutiveEndingToday_returnsZero() {
        final List<LocalDate> dates = List.of(TODAY.minusDays(4), TODAY.minusDays(3),
            TODAY.minusDays(2), TODAY.minusDays(1), TODAY);
        assertThat(Durations.days(StatsService.longestGap(dates, TODAY)))
            .as("unexpected value")
            .isZero();
    }

    // ── longestGap / longestStreak: the run's dates ───────────────────────────

    @Test
    void longestGap_historicalGap_spansTheBlankDaysBetweenTheTwoLoggedDates() {
        // Logged on day-20 and day-5: the blank run is day-19 … day-6 (14 days), NOT the 15-day distance
        // between the two logged dates.
        final List<LocalDate> dates = List.of(TODAY.minusDays(20), TODAY.minusDays(5), TODAY);
        assertThat(StatsService.longestGap(dates, TODAY))
            .as("the run starts the day after the earlier log and ends at (exclusive) the later log")
            .isEqualTo(new DaySpan(TODAY.minusDays(19), TODAY.minusDays(5)));
    }

    @Test
    void longestGap_openGap_spansTheDayAfterTheLastLogThroughToday() {
        assertThat(StatsService.longestGap(List.of(TODAY.minusDays(10)), TODAY))
            .as("the still-open run includes today, so its exclusive end is tomorrow")
            .isEqualTo(new DaySpan(TODAY.minusDays(9), TODAY.plusDays(1)));
    }

    @Test
    void longestGap_noGaps_isAnEmptySpanAtToday() {
        final List<LocalDate> dates = List.of(TODAY.minusDays(1), TODAY);
        assertThat(StatsService.longestGap(dates, TODAY))
            .as("nothing was ever missed, so there is no run to point at")
            .isEqualTo(new DaySpan(TODAY, TODAY));
    }

    @Test
    void longestStreak_spansTheWinningRunsOwnDates_notTheMostRecentRuns() {
        final List<LocalDate> dates = List.of(
            TODAY.minusDays(20), TODAY.minusDays(19),                     // run of 2
            TODAY.minusDays(10), TODAY.minusDays(9), TODAY.minusDays(8),  // run of 3 - the winner
            TODAY);                                                       // run of 1
        assertThat(StatsService.longestStreak(dates, TODAY))
            .as("the span is the historical winning run's real dates")
            .isEqualTo(new DaySpan(TODAY.minusDays(10), TODAY.minusDays(7)));
    }

    @Test
    void longestStreak_tie_keepsTheEarliestRun() {
        final List<LocalDate> dates = List.of(
            TODAY.minusDays(20), TODAY.minusDays(19),  // run of 2
            TODAY.minusDays(1), TODAY);                // run of 2, same length
        assertThat(StatsService.longestStreak(dates, TODAY))
            .as("a tie must not silently jump to the later run")
            .isEqualTo(new DaySpan(TODAY.minusDays(20), TODAY.minusDays(18)));
    }

    @Test
    void historicalRuns_labelDoesNotDriftAsTodayMovesOn() {
        // The reason these statistics carry dates instead of a day count. A 31-day streak that ran through
        // January is "1 month" whenever it is looked at; splitting a bare "31 days" against today would have
        // re-worded it every time the calendar moved (1 month / 1 month, 3 days / ...).
        final LocalDate streakStart = LocalDate.of(2026, 1, 16);
        final List<LocalDate> dates = new ArrayList<>();
        for (int day = 0; day < 31; day++) {
            dates.add(streakStart.plusDays(day));
        }

        final DaySpan asSeenInFebruary = StatsService.longestStreak(dates, LocalDate.of(2026, 2, 16));
        final DaySpan asSeenInMarch = StatsService.longestStreak(dates, LocalDate.of(2026, 3, 30));
        assertThat(asSeenInMarch)
            .as("a historical run's dates cannot depend on when they are read")
            .isEqualTo(asSeenInFebruary);
        assertThat(Durations.breakdown(asSeenInMarch))
            .as("unexpected value")
            .isEqualTo(Durations.breakdown(asSeenInFebruary));
        assertThat(Durations.breakdown(asSeenInMarch))
            .as("unexpected value")
            .isEqualTo(new DurationParts(0L, 1L, 0L));
    }

    @Test
    void longestGap_tie_keepsTheEarliestRun() {
        // Three 2-day gaps, then a 1-day open gap: every gap ties for longest except the open one.
        final List<LocalDate> dates = List.of(TODAY.minusDays(10), TODAY.minusDays(7), TODAY.minusDays(4), TODAY.minusDays(1));
        assertThat(StatsService.longestGap(dates, TODAY))
            .as("tied gaps: the earliest wins")
            .isEqualTo(new DaySpan(TODAY.minusDays(9), TODAY.minusDays(7)));
    }

    // ── longestStreak ─────────────────────────────────────────────────────────

    @Test
    void longestStreak_empty_returnsZero() {
        assertThat(Durations.days(StatsService.longestStreak(List.of(), TODAY)))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void longestStreak_singleDate_returnsOne() {
        assertThat(Durations.days(StatsService.longestStreak(List.of(TODAY), TODAY)))
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void longestStreak_twoConsecutive_returnsTwo() {
        assertThat(Durations.days(StatsService.longestStreak(List.of(TODAY.minusDays(1), TODAY), TODAY)))
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void longestStreak_twoWithGap_returnsOne() {
        assertThat(Durations.days(StatsService.longestStreak(List.of(TODAY.minusDays(2), TODAY), TODAY)))
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void longestStreak_longRunThenGapThenShortRun() {
        final List<LocalDate> dates = List.of(
            TODAY.minusDays(10),
            TODAY.minusDays(9),
            TODAY.minusDays(8),
            TODAY.minusDays(7),
            // gap
            TODAY.minusDays(2),
            TODAY.minusDays(1)
        );
        assertThat(Durations.days(StatsService.longestStreak(dates, TODAY)))
            .as("unexpected value")
            .isEqualTo(4);
    }

    @Test
    void longestStreak_allConsecutive_returnsTotalLength() {
        final List<LocalDate> fiveDays = List.of(
            TODAY.minusDays(4),
            TODAY.minusDays(3),
            TODAY.minusDays(2),
            TODAY.minusDays(1),
            TODAY
        );
        assertThat(Durations.days(StatsService.longestStreak(fiveDays, TODAY)))
            .as("unexpected value")
            .isEqualTo(5);
    }

    @Test
    void longestStreak_multipleRunsPicksLongest() {
        final List<LocalDate> dates = List.of(
            TODAY.minusDays(20), TODAY.minusDays(19),          // run of 2
            TODAY.minusDays(10), TODAY.minusDays(9), TODAY.minusDays(8), // run of 3
            TODAY                                               // run of 1
        );
        assertThat(Durations.days(StatsService.longestStreak(dates, TODAY)))
            .as("unexpected value")
            .isEqualTo(3);
    }

    // ── datesWithMultiples ────────────────────────────────────────────────────

    @Test
    void datesWithMultiples_empty_returnsEmpty() {
        assertThat(StatsService.datesWithMultiples(List.of()))
            .as("a subject with no recorded days has no days with multiples")
            .isEmpty();
    }

    @Test
    void datesWithMultiples_everyDayRecordedOnce_returnsEmpty() {
        final List<DailyActionTotal> days = List.of(day(TODAY.minusDays(2), 1L), day(TODAY.minusDays(1), 1L), day(TODAY, 1L));
        assertThat(StatsService.datesWithMultiples(days))
            .as("a day recorded exactly once does not count as a day with multiples")
            .isEmpty();
    }

    @Test
    void datesWithMultiples_keepsOnlyTheDaysRecordedMoreThanOnce() {
        final List<DailyActionTotal> days = List.of(
            day(TODAY.minusDays(3), 2L),
            day(TODAY.minusDays(2), 1L),
            day(TODAY.minusDays(1), 9L),
            day(TODAY, 1L));
        final List<LocalDate> expected = List.of(TODAY.minusDays(3), TODAY.minusDays(1));
        assertThat(StatsService.datesWithMultiples(days))
            .as("only the days recorded more than once are kept, in the order they were read")
            .containsExactlyElementsOf(expected);
    }

    private static DailyActionTotal day(final LocalDate date, final long total) {
        return new DailyActionTotal(UUID.randomUUID(), date, total);
    }
}
