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
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.time.DaySpan;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SubjectStatsTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    private static SubjectStats stats(
        final int totalDays, final long totalCount,
        @Nullable final LocalDate first, @Nullable final LocalDate last,
        final int currentStreak, final int longestStreak,
        final long thisMonth, final long lastMonth,
        final long thisYear, final long lastYear,
        final String bestMonthLabel, final long bestMonthCount,
        final String bestYearLabel, final long bestYearCount) {
        return statsG(totalDays, totalCount, first, last, currentStreak, longestStreak, 0,
                thisMonth, lastMonth, thisYear, lastYear,
                bestMonthLabel, bestMonthCount, bestYearLabel, bestYearCount);
    }

    private static SubjectStats statsG(
        final int totalDays, final long totalCount,
        @Nullable final LocalDate first, @Nullable final LocalDate last,
        final int currentStreak, final int longestStreak,
        final int longestGap,
        final long thisMonth, final long lastMonth,
        final long thisYear, final long lastYear,
        final String bestMonthLabel, final long bestMonthCount,
        final String bestYearLabel, final long bestYearCount) {
        return new SubjectStats(
            StatSubject.of(new Action()), totalDays, totalCount, first, last,
                span(currentStreak), span(longestStreak), span(longestGap),
                thisMonth, lastMonth,
                thisYear, lastYear,
                bestMonthLabel, bestMonthCount,
                bestYearLabel, bestYearCount,
                TODAY
        );
    }

    // Each streak/gap figure is a real date range; these tests only care about its LENGTH, so every span is
    // anchored to end today (see StatsServiceTest for the ranges themselves).
    private static DaySpan span(final int days) {
        return new DaySpan(TODAY.minusDays(days), TODAY);
    }

    // ── hasData ───────────────────────────────────────────────────────────────

    @Test
    void hasData_zeroTotalDays_returnsFalse() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.hasData(subjectStats))
            .as("expected condition to be false")
            .isFalse();
    }

    @Test
    void hasData_oneDayLogged_returnsTrue() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY, TODAY, 1, 1, 1L, 0L, 1L, 0L, "June 2025", 1L, "2025", 1L);
        assertThat(SubjectStatsExtensions.hasData(subjectStats))
            .as("expected condition to be true")
            .isTrue();
    }

    // ── latestLabel ─────────────────────────────────────────────────────────────

    @Test
    void latestLabel_nullLastPerformed_returnsNever() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.latestLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("Never");
    }

    @Test
    void latestLabel_currentYear_omitsYearAndComma() {
        final LocalDate sameYear = LocalDate.of(TODAY.getYear(), 3, 10);
        final SubjectStats subjectStats = stats(1, 1L, sameYear, sameYear, 0, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.latestLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("10 Mar");
    }

    @Test
    void latestLabel_previousYear_includesYear() {
        final LocalDate priorYear = LocalDate.of(TODAY.getYear() - 1, 12, 25);
        final SubjectStats subjectStats = stats(1, 1L, priorYear, priorYear, 0, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.latestLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("25 December " + (TODAY.getYear() - 1));
    }

    // ── firstLabel / sinceFirstLabel ────────────────────────────────────────────

    @Test
    void firstLabel_neverPerformed_returnsNever() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.firstLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("Never");
    }

    @Test
    void firstLabel_isTheFullWidthDate() {
        final SubjectStats subjectStats = stats(2, 2L, LocalDate.of(2024, 9, 3), TODAY, 1, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.firstLabel(subjectStats))
            .as("the month is spelled out; the front-end shortens it only if it does not fit")
            .isEqualTo("3 September 2024");
    }

    @Test
    void sinceFirstLabel_neverPerformed_returnsDash() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.sinceFirstLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("—");
    }

    @Test
    void sinceFirstLabel_startedToday_returnsToday() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY, TODAY, 1, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.sinceFirstLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("Today");
    }

    @Test
    void sinceFirstLabel_startedYesterday_returnsYesterday() {
        final SubjectStats subjectStats = stats(2, 2L, TODAY.minusDays(1L), TODAY, 2, 2, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.sinceFirstLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("Yesterday");
    }

    @Test
    void sinceFirstLabel_longHistory_isCondensed() {
        // TODAY is 15 June 2025, so a start of 3 September 2024 is 9 months and 12 days back.
        final SubjectStats subjectStats = stats(2, 2L, LocalDate.of(2024, 9, 3), TODAY, 1, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.sinceFirstLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("9 months, 12 days ago");
    }

    // ── performedThisMonth ──────────────────────────────────────────────────────

    @Test
    void performedThisMonth_zeroThisMonth_returnsFalse() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY.minusDays(40L), TODAY.minusDays(40L), 0, 1, 0L, 3L, 1L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.performedThisMonth(subjectStats))
            .as("expected condition to be false")
            .isFalse();
    }

    @Test
    void performedThisMonth_positiveThisMonth_returnsTrue() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY, TODAY, 1, 1, 2L, 0L, 2L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.performedThisMonth(subjectStats))
            .as("expected condition to be true")
            .isTrue();
    }

    // ── sinceLabel ────────────────────────────────────────────────────────────

    @Test
    void sinceLabel_nullLastPerformed_returnsDash() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.sinceLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("—");
    }

    @Test
    void sinceLabel_today_returnsToday() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY, TODAY, 1, 1, 1L, 0L, 1L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.sinceLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("Today");
    }

    @Test
    void sinceLabel_yesterday_returnsYesterday() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY.minusDays(1L), TODAY.minusDays(1L), 0, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.sinceLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("Yesterday");
    }

    @Test
    void sinceLabel_twoDaysAgo_returnsDaysAgoLabel() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY.minusDays(2L), TODAY.minusDays(2L), 0, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.sinceLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("2 days ago");
    }

    @Test
    void sinceLabel_thirtyDaysAgo() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY.minusDays(30L), TODAY.minusDays(30L), 0, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.sinceLabel(subjectStats))
            .as("unexpected value")
            .isEqualTo("30 days ago");
    }

    // ── weeklyDayAverage / monthlyDayAverage / weeklyCountAverage / monthlyCountAverage ────────

    @Test
    void weeklyDayAverage_nullFirstPerformed_returnsPlainZero() {
        // A zero average is simplified to a plain "0" (no trailing decimals) regardless of preference.
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyDayAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("0");
    }

    @Test
    void weeklyDayAverage_zeroAverage_ignoresDecimalPlaces() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyDayAverage(subjectStats, 3))
            .as("a zero average is always plain '0'")
            .isEqualTo("0");
    }

    @Test
    void weeklyDayAverage_oneOccurrenceInOneWeek_returnsOnePointZero() {
        // first = today-7, span = 1 week, totalDays=1 → 1/1 = 1.0
        final SubjectStats subjectStats = stats(1, 1L, TODAY.minusDays(7L), TODAY, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyDayAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("1.0");
    }

    @Test
    void weeklyDayAverage_sevenOccurrencesInOneWeek_returnsSevenPointZero() {
        // first = today-7, span = 1 week, totalDays=7 → 7/1 = 7.0
        // WEEKS.between(today-7, today) = 1; 7 days / 1 week = 7.0
        final SubjectStats subjectStats = stats(7, 7L, TODAY.minusDays(7L), TODAY, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyDayAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("7.0");
    }

    @Test
    void weeklyDayAverage_sevenOccurrencesInTwoWeeks_returnsThreePointFive() {
        // first = today-14, span = 2 weeks, totalDays=7 → 7/2 = 3.5
        final SubjectStats subjectStats = stats(7, 7L, TODAY.minusDays(14L), TODAY, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyDayAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("3.5");
    }

    @Test
    void weeklyDayAverage_twoDecimalPlaces_rendersTwoDecimals() {
        // first = today-14, span = 2 weeks, totalDays=7 → 7/2 = 3.5 → "3.50" at 2 places
        final SubjectStats subjectStats = stats(7, 7L, TODAY.minusDays(14L), TODAY, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyDayAverage(subjectStats, 2))
            .as("unexpected value")
            .isEqualTo("3.50");
    }

    @Test
    void weeklyDayAverage_zeroDecimalPlaces_roundsToWholeNumber() {
        // first = today-14, span = 2 weeks, totalDays=7 → 3.5 → "4" rounded to 0 places
        final SubjectStats subjectStats = stats(7, 7L, TODAY.minusDays(14L), TODAY, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyDayAverage(subjectStats, 0))
            .as("unexpected value")
            .isEqualTo("4");
    }

    @Test
    void monthlyDayAverage_threeDaysOverThreeMonths_isOnePerMonth() {
        final SubjectStats subjectStats = stats(3, 12L, TODAY.minusMonths(3L), TODAY, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.monthlyDayAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("1.0");
    }

    @Test
    void monthlyDayAverage_nullFirstPerformed_returnsPlainZero() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.monthlyDayAverage(subjectStats, 2))
            .as("unexpected value")
            .isEqualTo("0");
    }

    @Test
    void weeklyCountAverage_countsEveryRepeat_notJustActiveDays() {
        // 3 active days but 12 logged occurrences over 2 weeks: the DAY average is 1.5, the COUNT
        // average is 6.0 — the two stats must never collapse into one figure.
        final SubjectStats subjectStats = stats(3, 12L, TODAY.minusWeeks(2L), TODAY, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyDayAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("1.5");
        assertThat(SubjectStatsExtensions.weeklyCountAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("6.0");
    }

    @Test
    void weeklyCountAverage_nullFirstPerformed_returnsPlainZero() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyCountAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("0");
    }

    @Test
    void monthlyCountAverage_countsEveryRepeat_notJustActiveDays() {
        final SubjectStats subjectStats = stats(3, 12L, TODAY.minusMonths(3L), TODAY, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.monthlyCountAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("4.0");
    }

    @Test
    void monthlyCountAverage_nullFirstPerformed_returnsPlainZero() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.monthlyCountAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("0");
    }

    @Test
    void averages_spanShorterThanOnePeriod_dividesByOne() {
        // First performed yesterday: zero elapsed weeks/months, floored at one, so the average is the
        // total itself rather than a division by zero.
        final SubjectStats subjectStats = stats(2, 5L, TODAY.minusDays(1L), TODAY, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.weeklyDayAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("2.0");
        assertThat(SubjectStatsExtensions.monthlyCountAverage(subjectStats, 1))
            .as("unexpected value")
            .isEqualTo("5.0");
    }

    // ── currentGap ────────────────────────────────────────────────────────────

    @Test
    void currentGap_neverPerformed_isZero() {
        final SubjectStats subjectStats = stats(0, 0L, null, null, 0, 0, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.currentGap(subjectStats))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void currentGap_performedToday_isZero() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY, TODAY, 1, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.currentGap(subjectStats))
            .as("unexpected value")
            .isZero();
    }

    @Test
    void currentGap_performedEarlier_isTheElapsedDays() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY.minusDays(12L), TODAY.minusDays(12L), 0, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.currentGap(subjectStats))
            .as("unexpected value")
            .isEqualTo(12);
    }

    // ── totalDaysUnit ─────────────────────────────────────────────────────────

    @Test
    void totalDaysUnit_one_isSingular() {
        final SubjectStats subjectStats = stats(1, 1L, TODAY, TODAY, 1, 1, 0L, 0L, 0L, 0L, "—", 0L, "—", 0L);
        assertThat(SubjectStatsExtensions.totalDaysUnit(subjectStats))
            .as("unexpected value")
            .isEqualTo("unique day");
    }

    @Test
    void totalDaysUnit_zero_isPlural() {
        final SubjectStats subjectStats = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.totalDaysUnit(subjectStats))
            .as("unexpected value")
            .isEqualTo("unique days");
    }

    @Test
    void totalDaysUnit_many_isPlural() {
        final SubjectStats subjectStats = stats(2, 2, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.totalDaysUnit(subjectStats))
            .as("unexpected value")
            .isEqualTo("unique days");
    }

    // ── monthTrend / monthTrendClass ──────────────────────────────────────────

    @Test
    void monthTrend_bothZero_returnsDash() {
        final SubjectStats subjectStats = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.monthTrend(subjectStats))
            .as("unexpected value")
            .isEqualTo("—");
    }

    @Test
    void monthTrend_previousZeroCurrentPositive_returnsPositive() {
        final SubjectStats subjectStats = stats(1, 5, TODAY, TODAY, 0, 0, 5, 0, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.monthTrend(subjectStats))
            .as("unexpected value")
            .isEqualTo("+5");
        assertThat(SubjectStatsExtensions.monthTrendClass(subjectStats))
            .as("unexpected value")
            .isEqualTo("text-green-600");
    }

    @Test
    void monthTrend_equal_returnsEquals() {
        final SubjectStats subjectStats = stats(2, 4, TODAY, TODAY, 0, 0, 3, 3, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.monthTrend(subjectStats))
            .as("unexpected value")
            .isEqualTo("=");
        assertThat(SubjectStatsExtensions.monthTrendClass(subjectStats))
            .as("unexpected value")
            .isEqualTo("text-gray-400");
    }

    @Test
    void monthTrend_currentLessThanPrevious_returnsNegative() {
        final SubjectStats subjectStats = stats(2, 4, TODAY, TODAY, 0, 0, 1, 3, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.monthTrend(subjectStats))
            .as("unexpected value")
            .isEqualTo("-2");
        assertThat(SubjectStatsExtensions.monthTrendClass(subjectStats))
            .as("unexpected value")
            .isEqualTo("text-red-500");
    }

    @Test
    void monthTrend_currentGreaterThanPrevious_returnsPositive() {
        final SubjectStats subjectStats = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.monthTrend(subjectStats))
            .as("unexpected value")
            .isEqualTo("+3");
        assertThat(SubjectStatsExtensions.monthTrendClass(subjectStats))
            .as("unexpected value")
            .isEqualTo("text-green-600");
    }

    // ── yearTrend / yearTrendClass ────────────────────────────────────────────

    @Test
    void yearTrend_bothZero_returnsDash() {
        final SubjectStats subjectStats = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.yearTrend(subjectStats))
            .as("unexpected value")
            .isEqualTo("—");
    }

    @Test
    void yearTrend_currentGreater_returnsPositive() {
        final SubjectStats subjectStats = stats(1, 10, TODAY, TODAY, 0, 0, 0, 0, 10, 4, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.yearTrend(subjectStats))
            .as("unexpected value")
            .isEqualTo("+6");
        assertThat(SubjectStatsExtensions.yearTrendClass(subjectStats))
            .as("unexpected value")
            .isEqualTo("text-green-600");
    }

    @Test
    void yearTrend_currentLess_returnsNegative() {
        final SubjectStats subjectStats = stats(1, 5, TODAY, TODAY, 0, 0, 0, 0, 3, 8, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.yearTrend(subjectStats))
            .as("unexpected value")
            .isEqualTo("-5");
        assertThat(SubjectStatsExtensions.yearTrendClass(subjectStats))
            .as("unexpected value")
            .isEqualTo("text-red-500");
    }

    // ── monthContext / yearContext ─────────────────────────────────────────────

    @Test
    void monthContext_formatIsCorrect() {
        final SubjectStats subjectStats = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.monthContext(subjectStats))
            .as("unexpected value")
            .isEqualTo("5 this month · 2 last month");
    }

    @Test
    void thisMonthContext_formatIsCorrect() {
        final SubjectStats subjectStats = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.thisMonthContext(subjectStats))
            .as("unexpected value")
            .isEqualTo("5 this month");
    }

    @Test
    void yearContext_formatIsCorrect() {
        final SubjectStats subjectStats = stats(2, 7, TODAY, TODAY, 0, 0, 0, 0, 10, 4, "—", 0, "—", 0);
        assertThat(SubjectStatsExtensions.yearContext(subjectStats))
            .as("unexpected value")
            .isEqualTo("10 this year · 4 last year");
    }

    // ── tiles ──────────────────────────────────────────────────────────────────

    // A stat shown under its own catalogue label (the un-renamed case); a rename only swaps the caption,
    // which is covered in StatFieldTest.
    private static DisplayStat shown(final StatField field) {
        return new DisplayStat(field, field.label());
    }

    @Test
    void tiles_rendersInGivenFieldOrder() {
        final SubjectStats subjectStats = stats(1, 3, TODAY, TODAY, 4, 6, 5, 2, 0, 0, "—", 0, "—", 0);

        final List<StatTile> tiles = SubjectStatsExtensions.tiles(
            subjectStats, List.of(shown(StatField.TOTAL_COUNT), shown(StatField.CURRENT_STREAK)), 1);

        assertThat(tiles)
            .as("tiles render in the supplied field order")
            .extracting(StatTile::label)
            .containsExactly("Total count", "Current streak");
    }

    @Test
    void tiles_renamedStat_keepsItsFigureUnderTheUsersCaption() {
        final SubjectStats subjectStats = stats(1, 3, TODAY, TODAY, 4, 6, 5, 2, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions
            .tiles(subjectStats, List.of(new DisplayStat(StatField.TOTAL_COUNT, "Times done")), 1)
            .getFirst();

        assertThat(tile.label())
            .as("a renamed stat renders under the user's caption")
            .isEqualTo("Times done");
        assertThat(tile.value())
            .as("renaming a stat changes only its caption, never the figure it reports")
            .isEqualTo("3");
    }

    @Test
    void tiles_numericTile_carriesValueUnitAndDefaultClass() {
        final SubjectStats subjectStats = stats(1, 3, TODAY, TODAY, 1, 6, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.TOTAL_DAYS)), 1).getFirst();

        assertThat(tile.value())
            .as("total days value")
            .isEqualTo("1");
        assertThat(tile.sub())
            .as("singular unit for a total of one")
            .isEqualTo("unique day");
        assertThat(tile.subNum())
            .as("a unit word is not a locale-grouped number")
            .isFalse();
        assertThat(tile.valueClass())
            .as("numeric tiles use the default ink colour")
            .isEqualTo("text-ink");
        assertThat(tile.date())
            .as("a total is not a date tile")
            .isFalse();
    }

    @Test
    void tiles_weeklyDayAverage_honoursDecimalPlaces() {
        // 3 distinct days over exactly 2 weeks → 1.5 per week; rendered to 2 dp.
        final SubjectStats subjectStats = statsG(3, 3, TODAY.minusWeeks(2), TODAY, 0, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.WEEKLY_DAY_AVERAGE)), 2).getFirst();

        assertThat(tile.value())
            .as("weekly average uses the passed decimal-place count")
            .isEqualTo("1.50");
    }

    @Test
    void tiles_durationUnderOneMonth_stillWordsItsUnit() {
        // The whole point of the duration tiles: a short run reads "5 days", never a bare "5" with the
        // unit demoted to the sub-caption, so it matches the condensed form of a longer run beside it.
        final SubjectStats subjectStats = stats(5, 5, TODAY.minusDays(4), TODAY, 5, 5, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.CURRENT_STREAK)), 1).getFirst();

        assertThat(tile.value())
            .as("value carries the count and its unit")
            .isEqualTo("5 days");
        assertThat(tile.date())
            .as("a worded duration is not locale-grouped as a figure")
            .isTrue();
        assertThat(tile.subNum())
            .as("the sub carries dates, which must never be locale-grouped")
            .isFalse();
    }

    @Test
    void tiles_durationOfOneDay_isSingular() {
        final SubjectStats subjectStats = stats(1, 1, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.CURRENT_STREAK)), 1).getFirst();

        assertThat(tile.value())
            .as("a one-day run reads '1 day', never a bare '1'")
            .isEqualTo("1 day");
    }

    @Test
    void tiles_durationOverOneMonth_condenses() {
        // TODAY is 15 June 2025, so a 45-day streak reaches back to 1 May → "1 month, 14 days".
        final SubjectStats subjectStats = stats(45, 45, TODAY.minusDays(44), TODAY, 45, 45, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.CURRENT_STREAK)), 1).getFirst();

        assertThat(tile.value())
            .as("value is the condensed duration")
            .isEqualTo("1 month, 14 days");
    }

    @Test
    void tiles_ongoingDuration_subCaptionsTheStartDateOnly() {
        // The test spans end today, so this streak began on 10 June 2025 - and, being current, has no end.
        final SubjectStats subjectStats = stats(5, 5, TODAY.minusDays(4), TODAY, 5, 5, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.CURRENT_STREAK)), 1).getFirst();

        assertThat(tile.sub())
            .as("a still-running streak has no end date to show")
            .isEqualTo("since 10 June 2025");
    }

    @Test
    void tiles_closedDuration_subCaptionsBothEndsOfTheRun() {
        final SubjectStats subjectStats = stats(6, 6, TODAY.minusDays(5), TODAY, 0, 6, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.LONGEST_STREAK)), 1).getFirst();

        assertThat(tile.sub())
            .as("a completed run shows the dates it covered")
            .isEqualTo("9 June 2025 – 14 June 2025");
    }

    @Test
    void tiles_closedDurationOfOneDay_subCaptionsTheSingleDate() {
        final SubjectStats subjectStats = stats(1, 1, TODAY.minusDays(1), TODAY.minusDays(1), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.LONGEST_STREAK)), 1).getFirst();

        assertThat(tile.sub())
            .as("a one-day run names that day once, not as a range of it to itself")
            .isEqualTo("14 June 2025");
    }

    @Test
    void tiles_emptyDuration_hasNoSubCaption() {
        final SubjectStats subjectStats = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.CURRENT_STREAK)), 1).getFirst();

        assertThat(tile.value())
            .as("an empty run still words its unit")
            .isEqualTo("0 days");
        assertThat(tile.sub())
            .as("an empty run has no dates to caption")
            .isEmpty();
    }

    @Test
    void tiles_currentGap_isTheSpanSinceLastPerformed() {
        final SubjectStats subjectStats = stats(2, 2, TODAY.minusDays(9), TODAY.minusDays(9), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.CURRENT_GAP)), 1).getFirst();

        assertThat(tile.label())
            .as("unexpected value")
            .isEqualTo("Current gap");
        assertThat(tile.value())
            .as("nine days have elapsed since the action was last performed")
            .isEqualTo("9 days");
        assertThat(tile.sub())
            .as("the blank run starts the day after the action was last performed, and is still open")
            .isEqualTo("since 7 June 2025");
    }

    @Test
    void tiles_longestGap_subCaptionsTheBlankRunsDates() {
        final SubjectStats subjectStats = statsG(2, 2, TODAY.minusDays(20), TODAY, 0, 1, 4, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.LONGEST_GAP)), 1).getFirst();

        assertThat(tile.value())
            .as("unexpected value")
            .isEqualTo("4 days");
        assertThat(tile.sub())
            .as("a past gap is a closed run, so both of its ends are shown")
            .isEqualTo("11 June 2025 – 14 June 2025");
    }

    @Test
    void tiles_bestMonth_leadsWithTheMonthAndSubsTheCount() {
        final SubjectStats subjectStats = stats(2, 7, TODAY, TODAY, 0, 0, 0, 0, 0, 0, "June 2025", 21, "2025", 203);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.BEST_MONTH)), 1).getFirst();

        assertThat(tile.value())
            .as("the month is the headline, not the count")
            .isEqualTo("June 2025");
        assertThat(tile.sub())
            .as("the count is the secondary caption, singular-aware")
            .isEqualTo("21 times");
        assertThat(tile.date())
            .as("a month label uses the smaller date styling")
            .isTrue();
    }

    @Test
    void tiles_bestYear_leadsWithTheYearAndSubsTheCount() {
        final SubjectStats subjectStats = stats(2, 7, TODAY, TODAY, 0, 0, 0, 0, 0, 0, "June 2025", 21, "2025", 1);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.BEST_YEAR)), 1).getFirst();

        assertThat(tile.value())
            .as("the year is the headline, not the count")
            .isEqualTo("2025");
        assertThat(tile.sub())
            .as("a single occurrence reads '1 time', never '1 times'")
            .isEqualTo("1 time");
    }

    @Test
    void tiles_firstPerformed_isDateTileWithElapsedSub() {
        final SubjectStats subjectStats = stats(2, 4, TODAY.minusDays(3), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.FIRST_PERFORMED)), 1).getFirst();

        assertThat(tile.label())
            .as("unexpected value")
            .isEqualTo("First performed");
        assertThat(tile.date())
            .as("first-performed renders with the smaller date styling")
            .isTrue();
        assertThat(tile.value())
            .as("value is the first-performed date at full width")
            .isEqualTo("12 June 2025");
        assertThat(tile.sub())
            .as("sub is how long ago the user started")
            .isEqualTo("3 days ago");
    }

    @Test
    void tiles_lastPerformed_isDateTileWithSinceSub() {
        final SubjectStats subjectStats = stats(2, 4, TODAY.minusDays(3), TODAY.minusDays(3), 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.LAST_PERFORMED)), 1).getFirst();

        assertThat(tile.date())
            .as("last-performed renders with the smaller date styling")
            .isTrue();
        assertThat(tile.value())
            .as("value is the formatted date")
            .isEqualTo("12 June 2025");
        assertThat(tile.sub())
            .as("sub is the relative label")
            .isEqualTo("3 days ago");
        assertThat(tile.subNum())
            .as("the relative label carries a day count")
            .isTrue();
    }

    @Test
    void tiles_trendTile_carriesTrendColourClass() {
        final SubjectStats subjectStats = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);

        final StatTile tile = SubjectStatsExtensions.tiles(subjectStats, List.of(shown(StatField.VS_LAST_MONTH)), 1).getFirst();

        assertThat(tile.value())
            .as("upward month trend")
            .isEqualTo("+3");
        assertThat(tile.valueClass())
            .as("upward trend is green")
            .isEqualTo("text-green-600");
        assertThat(tile.sub())
            .as("sub carries the month context")
            .isEqualTo("5 this month · 2 last month");
        assertThat(tile.subNum())
            .as("context carries locale-groupable counts")
            .isTrue();
    }

    @Test
    void tiles_emptyFieldList_rendersNoTiles() {
        final SubjectStats subjectStats = stats(2, 7, TODAY, TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);

        assertThat(SubjectStatsExtensions.tiles(subjectStats, List.of(), 1))
            .as("no selected fields → no tiles")
            .isEmpty();
    }
}
