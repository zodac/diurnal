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
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    // ── currentStreak ─────────────────────────────────────────────────────────

    @Test
    void currentStreak_empty_returnsZero() {
        assertThat(StatsService.currentStreak(List.of(), TODAY))
            .as("unexpected value")
            .isEqualTo(0);
    }

    @Test
    void currentStreak_todayOnly_returnsOne() {
        assertThat(StatsService.currentStreak(List.of(TODAY), TODAY))
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void currentStreak_yesterdayOnly_returnsOne() {
        // Grace rule: yesterday counts when today has not been logged yet
        assertThat(StatsService.currentStreak(List.of(TODAY.minusDays(1)), TODAY))
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void currentStreak_todayAndYesterday_returnsTwo() {
        assertThat(StatsService.currentStreak(List.of(TODAY.minusDays(1), TODAY), TODAY))
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void currentStreak_threeDayRunEndingToday_returnsThree() {
        assertThat(StatsService.currentStreak(List.of(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), TODAY))
            .as("unexpected value")
            .isEqualTo(3);
    }

    @Test
    void currentStreak_threeDayRunEndingYesterday_returnsTwo() {
        // Today not logged — grace shifts cursor to yesterday, then back two more
        assertThat(StatsService.currentStreak(List.of(TODAY.minusDays(2), TODAY.minusDays(1)), TODAY))
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void currentStreak_gapBreaksRun() {
        // today-3, today-1, today — gap on today-2 breaks older portion
        assertThat(StatsService.currentStreak(List.of(TODAY.minusDays(3), TODAY.minusDays(1), TODAY), TODAY))
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void currentStreak_oldHistoryOnly_returnsZero() {
        // 30 days ago is neither today nor yesterday — no current streak
        assertThat(StatsService.currentStreak(List.of(TODAY.minusDays(30)), TODAY))
            .as("unexpected value")
            .isEqualTo(0);
    }

    @Test
    void currentStreak_twoOldEntriesThenGap_returnsZero() {
        assertThat(StatsService.currentStreak(List.of(TODAY.minusDays(5), TODAY.minusDays(4)), TODAY))
            .as("unexpected value")
            .isEqualTo(0);
    }

    @Test
    void currentStreak_streakIsCountedInDaysNotLogCount() {
        // Even if the same date appears multiple times (shouldn't happen via dedupe upstream,
        // but the Set construction should handle it gracefully)
        final List<LocalDate> repeated = List.of(TODAY, TODAY);
        // Set dedupe means streak = 1, not 2
        assertThat(StatsService.currentStreak(repeated, TODAY))
            .as("unexpected value")
            .isEqualTo(1);
    }

    // ── longestGap ────────────────────────────────────────────────────────────

    @Test
    void longestGap_empty_returnsZero() {
        assertThat(StatsService.longestGap(List.of(), TODAY))
            .as("unexpected value")
            .isEqualTo(0);
    }

    @Test
    void longestGap_singleEntryToday_returnsZero() {
        assertThat(StatsService.longestGap(List.of(TODAY), TODAY))
            .as("unexpected value")
            .isEqualTo(0);
    }

    @Test
    void longestGap_singleEntryYesterday_returnsOne() {
        assertThat(StatsService.longestGap(List.of(TODAY.minusDays(1)), TODAY))
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void longestGap_singleEntryTenDaysAgo_returnsTen() {
        assertThat(StatsService.longestGap(List.of(TODAY.minusDays(10)), TODAY))
            .as("unexpected value")
            .isEqualTo(10);
    }

    @Test
    void longestGap_twoConsecutiveEndingToday_returnsZero() {
        assertThat(StatsService.longestGap(List.of(TODAY.minusDays(1), TODAY), TODAY))
            .as("unexpected value")
            .isEqualTo(0);
    }

    @Test
    void longestGap_historicalGapLargerThanOpenGap() {
        // Gap between today-10 and today-1 = 8; open gap (today-1 to today) = 1
        final List<LocalDate> dates = List.of(TODAY.minusDays(10), TODAY.minusDays(1), TODAY);
        assertThat(StatsService.longestGap(dates, TODAY))
            .as("unexpected value")
            .isEqualTo(8);
    }

    @Test
    void longestGap_openGapLargerThanHistoricalGap() {
        // Gap between today-20 and today-18 = 1; open gap from today-18 to today = 18
        final List<LocalDate> dates = List.of(TODAY.minusDays(20), TODAY.minusDays(18));
        assertThat(StatsService.longestGap(dates, TODAY))
            .as("unexpected value")
            .isEqualTo(18);
    }

    @Test
    void longestGap_multipleGapsTakesLargest() {
        final List<LocalDate> dates = List.of(
            TODAY.minusDays(30), TODAY.minusDays(20), TODAY.minusDays(5), TODAY
        );
        // today-30 to today-20: 10 - 1 = 9; today-20 to today-5: 15 - 1 = 14; today-5 to today: 5 - 1 = 4; open: 0
        assertThat(StatsService.longestGap(dates, TODAY))
            .as("unexpected value")
            .isEqualTo(14);
    }

    @Test
    void longestGap_allConsecutiveEndingToday_returnsZero() {
        final List<LocalDate> dates = List.of(TODAY.minusDays(4), TODAY.minusDays(3),
            TODAY.minusDays(2), TODAY.minusDays(1), TODAY);
        assertThat(StatsService.longestGap(dates, TODAY))
            .as("unexpected value")
            .isEqualTo(0);
    }

    // ── longestStreak ─────────────────────────────────────────────────────────

    @Test
    void longestStreak_empty_returnsZero() {
        assertThat(StatsService.longestStreak(List.of()))
            .as("unexpected value")
            .isEqualTo(0);
    }

    @Test
    void longestStreak_singleDate_returnsOne() {
        assertThat(StatsService.longestStreak(List.of(TODAY)))
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void longestStreak_twoConsecutive_returnsTwo() {
        assertThat(StatsService.longestStreak(List.of(TODAY.minusDays(1), TODAY)))
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void longestStreak_twoWithGap_returnsOne() {
        assertThat(StatsService.longestStreak(List.of(TODAY.minusDays(2), TODAY)))
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
        assertThat(StatsService.longestStreak(dates))
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
        assertThat(StatsService.longestStreak(fiveDays))
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
        assertThat(StatsService.longestStreak(dates))
            .as("unexpected value")
            .isEqualTo(3);
    }
}
