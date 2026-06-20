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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import net.zodac.diurnal.action.Action;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ActionStatsTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    /** Builds an ActionStats with only the fields under test populated; everything else zeroed. */
    private static ActionStats stats(
            final int totalDays, final long totalCount,
            @Nullable final LocalDate first, @Nullable final LocalDate last,
            final int currentStreak, final int longestStreak,
            final long thisMonth, final long lastMonth,
            final long thisYear, final long lastYear,
            final String bestMonthLabel, final long bestMonthCount,
            final String bestYearLabel, final long bestYearCount) {
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
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertFalse(s.hasData(), "expected condition to be false");
    }

    @Test
    void hasData_oneDayLogged_returnsTrue() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 1, 0, 1, 0, "June 2025", 1, "2025", 1);
        assertTrue(s.hasData(), "expected condition to be true");
    }

    // ── latestLabel ─────────────────────────────────────────────────────────────

    @Test
    void latestLabel_nullLastPerformed_returnsNever() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("Never", s.latestLabel(), "unexpected value");
    }

    @Test
    void latestLabel_currentYear_omitsYearAndComma() {
        final LocalDate sameYear = LocalDate.of(TODAY.getYear(), 3, 10);
        final ActionStats s = stats(1, 1, sameYear, sameYear, 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("10 Mar", s.latestLabel(), "unexpected value");
    }

    @Test
    void latestLabel_previousYear_includesYear() {
        final LocalDate priorYear = LocalDate.of(TODAY.getYear() - 1, 12, 25);
        final ActionStats s = stats(1, 1, priorYear, priorYear, 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("25 Dec " + (TODAY.getYear() - 1), s.latestLabel(), "unexpected value");
    }

    // ── performedThisMonth ──────────────────────────────────────────────────────

    @Test
    void performedThisMonth_zeroThisMonth_returnsFalse() {
        final ActionStats s = stats(1, 1, TODAY.minusDays(40), TODAY.minusDays(40), 0, 1, 0, 3, 1, 0, "—", 0, "—", 0);
        assertFalse(s.performedThisMonth(), "expected condition to be false");
    }

    @Test
    void performedThisMonth_positiveThisMonth_returnsTrue() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 2, 0, 2, 0, "—", 0, "—", 0);
        assertTrue(s.performedThisMonth(), "expected condition to be true");
    }

    // ── sinceLabel ────────────────────────────────────────────────────────────

    @Test
    void sinceLabel_nullLastPerformed_returnsDash() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("—", s.sinceLabel(), "unexpected value");
    }

    @Test
    void sinceLabel_today_returnsToday() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 1, 0, 1, 0, "—", 0, "—", 0);
        assertEquals("Today", s.sinceLabel(), "unexpected value");
    }

    @Test
    void sinceLabel_yesterday_returnsYesterday() {
        final ActionStats s = stats(1, 1, TODAY.minusDays(1), TODAY.minusDays(1), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("Yesterday", s.sinceLabel(), "unexpected value");
    }

    @Test
    void sinceLabel_twoDaysAgo_returnsDaysAgoLabel() {
        final ActionStats s = stats(1, 1, TODAY.minusDays(2), TODAY.minusDays(2), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("2 days ago", s.sinceLabel(), "unexpected value");
    }

    @Test
    void sinceLabel_thirtyDaysAgo() {
        final ActionStats s = stats(1, 1, TODAY.minusDays(30), TODAY.minusDays(30), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("30 days ago", s.sinceLabel(), "unexpected value");
    }

    // ── weeklyAverage ─────────────────────────────────────────────────────────

    @Test
    void weeklyAverage_nullFirstPerformed_returnsZero() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("0.0", s.weeklyAverage(), "unexpected value");
    }

    @Test
    void weeklyAverage_oneOccurrenceInOneWeek_returnsOnePointZero() {
        // first = today-7, span = 1 week, totalDays=1 → 1/1 = 1.0
        final ActionStats s = stats(1, 1, TODAY.minusDays(7), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("1.0", s.weeklyAverage(), "unexpected value");
    }

    @Test
    void weeklyAverage_sevenOccurrencesInOneWeek_returnsSevenPointZero() {
        // first = today-7, span = 1 week, totalDays=7 → 7/1 = 7.0
        // WEEKS.between(today-7, today) = 1; 7 days / 1 week = 7.0
        final ActionStats s = stats(7, 7, TODAY.minusDays(7), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("7.0", s.weeklyAverage(), "unexpected value");
    }

    @Test
    void weeklyAverage_sevenOccurrencesInTwoWeeks_returnsThreePointFive() {
        // first = today-14, span = 2 weeks, totalDays=7 → 7/2 = 3.5
        final ActionStats s = stats(7, 7, TODAY.minusDays(14), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("3.5", s.weeklyAverage(), "unexpected value");
    }

    // ── streak / day labels (singular-aware) ───────────────────────────────────

    @Test
    void currentStreakLabel_one_isSingular() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("1 day", s.currentStreakLabel(), "unexpected value");
    }

    @Test
    void currentStreakLabel_zero_isPlural() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("0 days", s.currentStreakLabel(), "unexpected value");
    }

    @Test
    void currentStreakLabel_many_isPlural() {
        final ActionStats s = stats(5, 5, TODAY, TODAY, 5, 5, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("5 days", s.currentStreakLabel(), "unexpected value");
    }

    @Test
    void longestStreakLabel_one_isSingular() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("1 day", s.longestStreakLabel(), "unexpected value");
    }

    @Test
    void longestStreakLabel_many_isPlural() {
        final ActionStats s = stats(3, 3, TODAY, TODAY, 1, 3, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("3 days", s.longestStreakLabel(), "unexpected value");
    }

    @Test
    void totalDaysUnit_one_isSingular() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("distinct day", s.totalDaysUnit(), "unexpected value");
    }

    @Test
    void totalDaysUnit_zero_isPlural() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("distinct days", s.totalDaysUnit(), "unexpected value");
    }

    @Test
    void totalDaysUnit_many_isPlural() {
        final ActionStats s = stats(2, 2, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("distinct days", s.totalDaysUnit(), "unexpected value");
    }

    // ── monthTrend / monthTrendClass ──────────────────────────────────────────

    @Test
    void monthTrend_bothZero_returnsDash() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("—", s.monthTrend(), "unexpected value");
    }

    @Test
    void monthTrend_previousZeroCurrentPositive_returnsPositive() {
        final ActionStats s = stats(1, 5, TODAY, TODAY, 0, 0, 5, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("+5", s.monthTrend(), "unexpected value");
        assertEquals("text-green-600", s.monthTrendClass(), "unexpected value");
    }

    @Test
    void monthTrend_equal_returnsEquals() {
        final ActionStats s = stats(2, 4, TODAY, TODAY, 0, 0, 3, 3, 0, 0, "—", 0, "—", 0);
        assertEquals("=", s.monthTrend(), "unexpected value");
        assertEquals("text-gray-400", s.monthTrendClass(), "unexpected value");
    }

    @Test
    void monthTrend_currentLessThanPrevious_returnsNegative() {
        final ActionStats s = stats(2, 4, TODAY, TODAY, 0, 0, 1, 3, 0, 0, "—", 0, "—", 0);
        assertEquals("-2", s.monthTrend(), "unexpected value");
        assertEquals("text-red-500", s.monthTrendClass(), "unexpected value");
    }

    @Test
    void monthTrend_currentGreaterThanPrevious_returnsPositive() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertEquals("+3", s.monthTrend(), "unexpected value");
        assertEquals("text-green-600", s.monthTrendClass(), "unexpected value");
    }

    // ── yearTrend / yearTrendClass ────────────────────────────────────────────

    @Test
    void yearTrend_bothZero_returnsDash() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertEquals("—", s.yearTrend(), "unexpected value");
    }

    @Test
    void yearTrend_currentGreater_returnsPositive() {
        final ActionStats s = stats(1, 10, TODAY, TODAY, 0, 0, 0, 0, 10, 4, "—", 0, "—", 0);
        assertEquals("+6", s.yearTrend(), "unexpected value");
        assertEquals("text-green-600", s.yearTrendClass(), "unexpected value");
    }

    @Test
    void yearTrend_currentLess_returnsNegative() {
        final ActionStats s = stats(1, 5, TODAY, TODAY, 0, 0, 0, 0, 3, 8, "—", 0, "—", 0);
        assertEquals("-5", s.yearTrend(), "unexpected value");
        assertEquals("text-red-500", s.yearTrendClass(), "unexpected value");
    }

    // ── monthContext / yearContext ─────────────────────────────────────────────

    @Test
    void monthContext_formatIsCorrect() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertEquals("5 this month · 2 last month", s.monthContext(), "unexpected value");
    }

    @Test
    void thisMonthContext_formatIsCorrect() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertEquals("5 this month", s.thisMonthContext(), "unexpected value");
    }

    @Test
    void yearContext_formatIsCorrect() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 0, 0, 10, 4, "—", 0, "—", 0);
        assertEquals("10 this year · 4 last year", s.yearContext(), "unexpected value");
    }
}
