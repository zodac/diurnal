package net.zodac.diurnal.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatsServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    // ── currentStreak ─────────────────────────────────────────────────────────

    @Test
    void currentStreak_empty_returnsZero() {
        assertEquals(0, StatsService.currentStreak(List.of(), TODAY));
    }

    @Test
    void currentStreak_todayOnly_returnsOne() {
        assertEquals(1, StatsService.currentStreak(List.of(TODAY), TODAY));
    }

    @Test
    void currentStreak_yesterdayOnly_returnsOne() {
        // Grace rule: yesterday counts when today has not been logged yet
        assertEquals(1, StatsService.currentStreak(List.of(TODAY.minusDays(1)), TODAY));
    }

    @Test
    void currentStreak_todayAndYesterday_returnsTwo() {
        assertEquals(2, StatsService.currentStreak(
                List.of(TODAY.minusDays(1), TODAY), TODAY));
    }

    @Test
    void currentStreak_threeDayRunEndingToday_returnsThree() {
        assertEquals(3, StatsService.currentStreak(
                List.of(TODAY.minusDays(2), TODAY.minusDays(1), TODAY), TODAY));
    }

    @Test
    void currentStreak_threeDayRunEndingYesterday_returnsTwo() {
        // Today not logged — grace shifts cursor to yesterday, then back two more
        assertEquals(2, StatsService.currentStreak(
                List.of(TODAY.minusDays(2), TODAY.minusDays(1)), TODAY));
    }

    @Test
    void currentStreak_gapBreaksRun() {
        // today-3, today-1, today — gap on today-2 breaks older portion
        assertEquals(2, StatsService.currentStreak(
                List.of(TODAY.minusDays(3), TODAY.minusDays(1), TODAY), TODAY));
    }

    @Test
    void currentStreak_oldHistoryOnly_returnsZero() {
        // 30 days ago is neither today nor yesterday — no current streak
        assertEquals(0, StatsService.currentStreak(
                List.of(TODAY.minusDays(30)), TODAY));
    }

    @Test
    void currentStreak_twoOldEntriesThenGap_returnsZero() {
        assertEquals(0, StatsService.currentStreak(
                List.of(TODAY.minusDays(5), TODAY.minusDays(4)), TODAY));
    }

    @Test
    void currentStreak_streakIsCountedInDaysNotLogCount() {
        // Even if the same date appears multiple times (shouldn't happen via dedupe upstream,
        // but the Set construction should handle it gracefully)
        List<LocalDate> repeated = List.of(TODAY, TODAY);
        // Set dedupe means streak = 1, not 2
        assertEquals(1, StatsService.currentStreak(repeated, TODAY));
    }

    // ── longestStreak ─────────────────────────────────────────────────────────

    @Test
    void longestStreak_empty_returnsZero() {
        assertEquals(0, StatsService.longestStreak(List.of()));
    }

    @Test
    void longestStreak_singleDate_returnsOne() {
        assertEquals(1, StatsService.longestStreak(List.of(TODAY)));
    }

    @Test
    void longestStreak_twoConsecutive_returnsTwo() {
        assertEquals(2, StatsService.longestStreak(
                List.of(TODAY.minusDays(1), TODAY)));
    }

    @Test
    void longestStreak_twoWithGap_returnsOne() {
        assertEquals(1, StatsService.longestStreak(
                List.of(TODAY.minusDays(2), TODAY)));
    }

    @Test
    void longestStreak_longRunThenGapThenShortRun() {
        List<LocalDate> dates = List.of(
                TODAY.minusDays(10),
                TODAY.minusDays(9),
                TODAY.minusDays(8),
                TODAY.minusDays(7),
                // gap
                TODAY.minusDays(2),
                TODAY.minusDays(1)
        );
        assertEquals(4, StatsService.longestStreak(dates));
    }

    @Test
    void longestStreak_allConsecutive_returnsTotalLength() {
        List<LocalDate> fiveDays = List.of(
                TODAY.minusDays(4),
                TODAY.minusDays(3),
                TODAY.minusDays(2),
                TODAY.minusDays(1),
                TODAY
        );
        assertEquals(5, StatsService.longestStreak(fiveDays));
    }

    @Test
    void longestStreak_multipleRunsPicksLongest() {
        List<LocalDate> dates = List.of(
                TODAY.minusDays(20), TODAY.minusDays(19),          // run of 2
                TODAY.minusDays(10), TODAY.minusDays(9), TODAY.minusDays(8), // run of 3
                TODAY                                               // run of 1
        );
        assertEquals(3, StatsService.longestStreak(dates));
    }
}
