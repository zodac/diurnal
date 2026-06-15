package net.zodac.diurnal.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.zodac.diurnal.action.Action;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ActionStatsTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    /** Builds an ActionStats with only the fields under test populated; everything else zeroed. */
    private static ActionStats stats(
            int totalDays, long totalCount,
            LocalDate first, LocalDate last,
            int currentStreak, int longestStreak,
            long thisMonth, long lastMonth,
            long thisYear, long lastYear,
            String bestMonthLabel, long bestMonthCount,
            String bestYearLabel, long bestYearCount) {
        return new ActionStats(
                new Action(), totalDays, totalCount, first, last,
                currentStreak, longestStreak,
                thisMonth, lastMonth,
                thisYear, lastYear,
                bestMonthLabel, bestMonthCount,
                bestYearLabel, bestYearCount,
                TODAY
        );
    }

    // ── hasData ───────────────────────────────────────────────────────────────

    @Test
    void hasData_zeroTotalDays_returnsFalse() {
        ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertFalse(s.hasData());
    }

    @Test
    void hasData_oneDayLogged_returnsTrue() {
        ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 1, 0, 1, 0, "June 2025", 1, "2025", 1);
        assertTrue(s.hasData());
    }

    // ── sinceLabel ────────────────────────────────────────────────────────────

    @Test
    void sinceLabel_nullLastPerformed_returnsDash() {
        ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("—", s.sinceLabel());
    }

    @Test
    void sinceLabel_today_returnsToday() {
        ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 1, 0, 1, 0, "—", 0, "—", 0);
        assertEquals("Today", s.sinceLabel());
    }

    @Test
    void sinceLabel_yesterday_returnsYesterday() {
        ActionStats s = stats(1, 1, TODAY.minusDays(1), TODAY.minusDays(1), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("Yesterday", s.sinceLabel());
    }

    @Test
    void sinceLabel_twoDaysAgo_returnsDaysAgoLabel() {
        ActionStats s = stats(1, 1, TODAY.minusDays(2), TODAY.minusDays(2), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("2 days ago", s.sinceLabel());
    }

    @Test
    void sinceLabel_thirtyDaysAgo() {
        ActionStats s = stats(1, 1, TODAY.minusDays(30), TODAY.minusDays(30), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("30 days ago", s.sinceLabel());
    }

    // ── weeklyAverage ─────────────────────────────────────────────────────────

    @Test
    void weeklyAverage_nullFirstPerformed_returnsZero() {
        ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("0.0", s.weeklyAverage());
    }

    @Test
    void weeklyAverage_oneOccurrenceInOneWeek_returnsOnePointZero() {
        // first = today-7, span = 1 week, totalDays=1 → 1/1 = 1.0
        ActionStats s = stats(1, 1, TODAY.minusDays(7), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("1.0", s.weeklyAverage());
    }

    @Test
    void weeklyAverage_sevenOccurrencesInOneWeek_returnsSevenPointZero() {
        // first = today-7, span = 1 week, totalDays=7 → 7/1 = 7.0
        // WEEKS.between(today-7, today) = 1; 7 days / 1 week = 7.0
        ActionStats s = stats(7, 7, TODAY.minusDays(7), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("7.0", s.weeklyAverage());
    }

    @Test
    void weeklyAverage_sevenOccurrencesInTwoWeeks_returnsThreePointFive() {
        // first = today-14, span = 2 weeks, totalDays=7 → 7/2 = 3.5
        ActionStats s = stats(7, 7, TODAY.minusDays(14), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("3.5", s.weeklyAverage());
    }

    // ── monthTrend / monthTrendClass ──────────────────────────────────────────

    @Test
    void monthTrend_bothZero_returnsDash() {
        ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("—", s.monthTrend());
    }

    @Test
    void monthTrend_previousZeroCurrentPositive_returnsPositive() {
        ActionStats s = stats(1, 5, TODAY, TODAY, 0, 0, 5, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("+5", s.monthTrend());
        assertEquals("text-green-600", s.monthTrendClass());
    }

    @Test
    void monthTrend_equal_returnsEquals() {
        ActionStats s = stats(2, 4, TODAY, TODAY, 0, 0, 3, 3, 0, 0, "—", 0, "—", 0);
        assertEquals("=", s.monthTrend());
        assertEquals("text-gray-400", s.monthTrendClass());
    }

    @Test
    void monthTrend_currentLessThanPrevious_returnsNegative() {
        ActionStats s = stats(2, 4, TODAY, TODAY, 0, 0, 1, 3, 0, 0, "—", 0, "—", 0);
        assertEquals("-2", s.monthTrend());
        assertEquals("text-red-500", s.monthTrendClass());
    }

    @Test
    void monthTrend_currentGreaterThanPrevious_returnsPositive() {
        ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertEquals("+3", s.monthTrend());
        assertEquals("text-green-600", s.monthTrendClass());
    }

    // ── yearTrend / yearTrendClass ────────────────────────────────────────────

    @Test
    void yearTrend_bothZero_returnsDash() {
        ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("—", s.yearTrend());
    }

    @Test
    void yearTrend_currentGreater_returnsPositive() {
        ActionStats s = stats(1, 10, TODAY, TODAY, 0, 0, 0, 0, 10, 4, "—", 0, "—", 0);
        assertEquals("+6", s.yearTrend());
        assertEquals("text-green-600", s.yearTrendClass());
    }

    @Test
    void yearTrend_currentLess_returnsNegative() {
        ActionStats s = stats(1, 5, TODAY, TODAY, 0, 0, 0, 0, 3, 8, "—", 0, "—", 0);
        assertEquals("-5", s.yearTrend());
        assertEquals("text-red-500", s.yearTrendClass());
    }

    // ── monthContext / yearContext ─────────────────────────────────────────────

    @Test
    void monthContext_formatIsCorrect() {
        ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertEquals("5 this month · 2 last month", s.monthContext());
    }

    @Test
    void yearContext_formatIsCorrect() {
        ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 0, 0, 10, 4, "—", 0, "—", 0);
        assertEquals("10 this year · 4 last year", s.yearContext());
    }
}
