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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class ActionStatsTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    private static ActionStats stats(
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

    private static ActionStats statsG(
            final int totalDays, final long totalCount,
            @Nullable final LocalDate first, @Nullable final LocalDate last,
            final int currentStreak, final int longestStreak,
            final int longestGap,
            final long thisMonth, final long lastMonth,
            final long thisYear, final long lastYear,
            final String bestMonthLabel, final long bestMonthCount,
            final String bestYearLabel, final long bestYearCount) {
        return new ActionStats(
                new Action(), totalDays, totalCount, first, last,
                currentStreak, longestStreak, longestGap,
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
        assertThat(ActionStatsExtensions.hasData(s))
            .as("expected condition to be false")
            .isFalse();
    }

    @Test
    void hasData_oneDayLogged_returnsTrue() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 1, 0, 1, 0, "June 2025", 1, "2025", 1);
        assertThat(ActionStatsExtensions.hasData(s))
            .as("expected condition to be true")
            .isTrue();
    }

    // ── latestLabel ─────────────────────────────────────────────────────────────

    @Test
    void latestLabel_nullLastPerformed_returnsNever() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.latestLabel(s))
            .as("unexpected value")
            .isEqualTo("Never");
    }

    @Test
    void latestLabel_currentYear_omitsYearAndComma() {
        final LocalDate sameYear = LocalDate.of(TODAY.getYear(), 3, 10);
        final ActionStats s = stats(1, 1, sameYear, sameYear, 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.latestLabel(s))
            .as("unexpected value")
            .isEqualTo("10 Mar");
    }

    @Test
    void latestLabel_previousYear_includesYear() {
        final LocalDate priorYear = LocalDate.of(TODAY.getYear() - 1, 12, 25);
        final ActionStats s = stats(1, 1, priorYear, priorYear, 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.latestLabel(s))
            .as("unexpected value")
            .isEqualTo("25 Dec " + (TODAY.getYear() - 1));
    }

    // ── performedThisMonth ──────────────────────────────────────────────────────

    @Test
    void performedThisMonth_zeroThisMonth_returnsFalse() {
        final ActionStats s = stats(1, 1, TODAY.minusDays(40), TODAY.minusDays(40), 0, 1, 0, 3, 1, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.performedThisMonth(s))
            .as("expected condition to be false")
            .isFalse();
    }

    @Test
    void performedThisMonth_positiveThisMonth_returnsTrue() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 2, 0, 2, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.performedThisMonth(s))
            .as("expected condition to be true")
            .isTrue();
    }

    // ── sinceLabel ────────────────────────────────────────────────────────────

    @Test
    void sinceLabel_nullLastPerformed_returnsDash() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.sinceLabel(s))
            .as("unexpected value")
            .isEqualTo("—");
    }

    @Test
    void sinceLabel_today_returnsToday() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 1, 0, 1, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.sinceLabel(s))
            .as("unexpected value")
            .isEqualTo("Today");
    }

    @Test
    void sinceLabel_yesterday_returnsYesterday() {
        final ActionStats s = stats(1, 1, TODAY.minusDays(1), TODAY.minusDays(1), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.sinceLabel(s))
            .as("unexpected value")
            .isEqualTo("Yesterday");
    }

    @Test
    void sinceLabel_twoDaysAgo_returnsDaysAgoLabel() {
        final ActionStats s = stats(1, 1, TODAY.minusDays(2), TODAY.minusDays(2), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.sinceLabel(s))
            .as("unexpected value")
            .isEqualTo("2 days ago");
    }

    @Test
    void sinceLabel_thirtyDaysAgo() {
        final ActionStats s = stats(1, 1, TODAY.minusDays(30), TODAY.minusDays(30), 0, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.sinceLabel(s))
            .as("unexpected value")
            .isEqualTo("30 days ago");
    }

    // ── weeklyAverage ─────────────────────────────────────────────────────────

    @Test
    void weeklyAverage_nullFirstPerformed_returnsPlainZero() {
        // A zero average is simplified to a plain "0" (no trailing decimals) regardless of preference.
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.weeklyAverage(s, 1))
            .as("unexpected value")
            .isEqualTo("0");
    }

    @Test
    void weeklyAverage_zeroAverage_ignoresDecimalPlaces() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.weeklyAverage(s, 3))
            .as("a zero average is always plain '0'")
            .isEqualTo("0");
    }

    @Test
    void weeklyAverage_oneOccurrenceInOneWeek_returnsOnePointZero() {
        // first = today-7, span = 1 week, totalDays=1 → 1/1 = 1.0
        final ActionStats s = stats(1, 1, TODAY.minusDays(7), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.weeklyAverage(s, 1))
            .as("unexpected value")
            .isEqualTo("1.0");
    }

    @Test
    void weeklyAverage_sevenOccurrencesInOneWeek_returnsSevenPointZero() {
        // first = today-7, span = 1 week, totalDays=7 → 7/1 = 7.0
        // WEEKS.between(today-7, today) = 1; 7 days / 1 week = 7.0
        final ActionStats s = stats(7, 7, TODAY.minusDays(7), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.weeklyAverage(s, 1))
            .as("unexpected value")
            .isEqualTo("7.0");
    }

    @Test
    void weeklyAverage_sevenOccurrencesInTwoWeeks_returnsThreePointFive() {
        // first = today-14, span = 2 weeks, totalDays=7 → 7/2 = 3.5
        final ActionStats s = stats(7, 7, TODAY.minusDays(14), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.weeklyAverage(s, 1))
            .as("unexpected value")
            .isEqualTo("3.5");
    }

    @Test
    void weeklyAverage_twoDecimalPlaces_rendersTwoDecimals() {
        // first = today-14, span = 2 weeks, totalDays=7 → 7/2 = 3.5 → "3.50" at 2 places
        final ActionStats s = stats(7, 7, TODAY.minusDays(14), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.weeklyAverage(s, 2))
            .as("unexpected value")
            .isEqualTo("3.50");
    }

    @Test
    void weeklyAverage_zeroDecimalPlaces_roundsToWholeNumber() {
        // first = today-14, span = 2 weeks, totalDays=7 → 3.5 → "4" rounded to 0 places
        final ActionStats s = stats(7, 7, TODAY.minusDays(14), TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.weeklyAverage(s, 0))
            .as("unexpected value")
            .isEqualTo("4");
    }

    // ── streak / day labels (singular-aware) ───────────────────────────────────

    @Test
    void currentStreakLabel_one_isSingular() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.currentStreakLabel(s))
            .as("unexpected value")
            .isEqualTo("1 day");
    }

    @Test
    void currentStreakLabel_zero_isPlural() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.currentStreakLabel(s))
            .as("unexpected value")
            .isEqualTo("0 days");
    }

    @Test
    void currentStreakLabel_many_isPlural() {
        final ActionStats s = stats(5, 5, TODAY, TODAY, 5, 5, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.currentStreakLabel(s))
            .as("unexpected value")
            .isEqualTo("5 days");
    }

    @Test
    void longestStreakLabel_one_isSingular() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.longestStreakLabel(s))
            .as("unexpected value")
            .isEqualTo("1 day");
    }

    @Test
    void longestStreakLabel_many_isPlural() {
        final ActionStats s = stats(3, 3, TODAY, TODAY, 1, 3, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.longestStreakLabel(s))
            .as("unexpected value")
            .isEqualTo("3 days");
    }

    @Test
    void totalDaysUnit_one_isSingular() {
        final ActionStats s = stats(1, 1, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.totalDaysUnit(s))
            .as("unexpected value")
            .isEqualTo("distinct day");
    }

    @Test
    void totalDaysUnit_zero_isPlural() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.totalDaysUnit(s))
            .as("unexpected value")
            .isEqualTo("distinct days");
    }

    @Test
    void totalDaysUnit_many_isPlural() {
        final ActionStats s = stats(2, 2, TODAY, TODAY, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.totalDaysUnit(s))
            .as("unexpected value")
            .isEqualTo("distinct days");
    }

    // ── longestGapLabel / longestGapUnit ──────────────────────────────────────

    @Test
    void longestGapLabel_zero_isPlural() {
        final ActionStats s = statsG(0, 0, null, null, 0, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.longestGapLabel(s))
            .as("unexpected value")
            .isEqualTo("0 days");
    }

    @Test
    void longestGapLabel_one_isSingular() {
        final ActionStats s = statsG(1, 1, TODAY, TODAY, 0, 1, 1, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.longestGapLabel(s))
            .as("unexpected value")
            .isEqualTo("1 day");
    }

    @Test
    void longestGapLabel_many_isPlural() {
        final ActionStats s = statsG(3, 3, TODAY, TODAY, 1, 3, 7, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.longestGapLabel(s))
            .as("unexpected value")
            .isEqualTo("7 days");
    }

    // ── monthTrend / monthTrendClass ──────────────────────────────────────────

    @Test
    void monthTrend_bothZero_returnsDash() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.monthTrend(s))
            .as("unexpected value")
            .isEqualTo("—");
    }

    @Test
    void monthTrend_previousZeroCurrentPositive_returnsPositive() {
        final ActionStats s = stats(1, 5, TODAY, TODAY, 0, 0, 5, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.monthTrend(s))
            .as("unexpected value")
            .isEqualTo("+5");
        assertThat(ActionStatsExtensions.monthTrendClass(s))
            .as("unexpected value")
            .isEqualTo("text-green-600");
    }

    @Test
    void monthTrend_equal_returnsEquals() {
        final ActionStats s = stats(2, 4, TODAY, TODAY, 0, 0, 3, 3, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.monthTrend(s))
            .as("unexpected value")
            .isEqualTo("=");
        assertThat(ActionStatsExtensions.monthTrendClass(s))
            .as("unexpected value")
            .isEqualTo("text-gray-400");
    }

    @Test
    void monthTrend_currentLessThanPrevious_returnsNegative() {
        final ActionStats s = stats(2, 4, TODAY, TODAY, 0, 0, 1, 3, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.monthTrend(s))
            .as("unexpected value")
            .isEqualTo("-2");
        assertThat(ActionStatsExtensions.monthTrendClass(s))
            .as("unexpected value")
            .isEqualTo("text-red-500");
    }

    @Test
    void monthTrend_currentGreaterThanPrevious_returnsPositive() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.monthTrend(s))
            .as("unexpected value")
            .isEqualTo("+3");
        assertThat(ActionStatsExtensions.monthTrendClass(s))
            .as("unexpected value")
            .isEqualTo("text-green-600");
    }

    // ── yearTrend / yearTrendClass ────────────────────────────────────────────

    @Test
    void yearTrend_bothZero_returnsDash() {
        final ActionStats s = stats(0, 0, null, null, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.yearTrend(s))
            .as("unexpected value")
            .isEqualTo("—");
    }

    @Test
    void yearTrend_currentGreater_returnsPositive() {
        final ActionStats s = stats(1, 10, TODAY, TODAY, 0, 0, 0, 0, 10, 4, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.yearTrend(s))
            .as("unexpected value")
            .isEqualTo("+6");
        assertThat(ActionStatsExtensions.yearTrendClass(s))
            .as("unexpected value")
            .isEqualTo("text-green-600");
    }

    @Test
    void yearTrend_currentLess_returnsNegative() {
        final ActionStats s = stats(1, 5, TODAY, TODAY, 0, 0, 0, 0, 3, 8, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.yearTrend(s))
            .as("unexpected value")
            .isEqualTo("-5");
        assertThat(ActionStatsExtensions.yearTrendClass(s))
            .as("unexpected value")
            .isEqualTo("text-red-500");
    }

    // ── monthContext / yearContext ─────────────────────────────────────────────

    @Test
    void monthContext_formatIsCorrect() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.monthContext(s))
            .as("unexpected value")
            .isEqualTo("5 this month · 2 last month");
    }

    @Test
    void thisMonthContext_formatIsCorrect() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.thisMonthContext(s))
            .as("unexpected value")
            .isEqualTo("5 this month");
    }

    @Test
    void yearContext_formatIsCorrect() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 0, 0, 10, 4, "—", 0, "—", 0);
        assertThat(ActionStatsExtensions.yearContext(s))
            .as("unexpected value")
            .isEqualTo("10 this year · 4 last year");
    }

    // ── tiles ──────────────────────────────────────────────────────────────────

    @Test
    void tiles_rendersInGivenFieldOrder() {
        final ActionStats s = stats(1, 3, TODAY, TODAY, 4, 6, 5, 2, 0, 0, "—", 0, "—", 0);

        final List<StatTile> tiles = ActionStatsExtensions.tiles(
            s, List.of(ActionStatField.TOTAL_COUNT, ActionStatField.CURRENT_STREAK), 1);

        assertThat(tiles)
            .as("tiles render in the supplied field order")
            .extracting(StatTile::label)
            .containsExactly("Total count", "Current streak");
    }

    @Test
    void tiles_numericTile_carriesValueUnitAndDefaultClass() {
        final ActionStats s = stats(1, 3, TODAY, TODAY, 1, 6, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = ActionStatsExtensions.tiles(s, List.of(ActionStatField.CURRENT_STREAK), 1).getFirst();

        assertThat(tile.value()).as("current streak value").isEqualTo("1");
        assertThat(tile.sub()).as("singular unit for a streak of one").isEqualTo("day");
        assertThat(tile.subNum()).as("a unit word is not a locale-grouped number").isFalse();
        assertThat(tile.valueClass()).as("numeric tiles use the default ink colour").isEqualTo("text-ink");
        assertThat(tile.date()).as("a streak is not a date tile").isFalse();
    }

    @Test
    void tiles_weeklyAverage_honoursDecimalPlaces() {
        // 3 distinct days over exactly 2 weeks → 1.5 per week; rendered to 2 dp.
        final ActionStats s = statsG(3, 3, TODAY.minusWeeks(2), TODAY, 0, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = ActionStatsExtensions.tiles(s, List.of(ActionStatField.WEEKLY_AVERAGE), 2).getFirst();

        assertThat(tile.value()).as("weekly average uses the passed decimal-place count").isEqualTo("1.50");
    }

    @Test
    void tiles_lastPerformed_isDateTileWithSinceSub() {
        final ActionStats s = stats(2, 4, TODAY.minusDays(3), TODAY.minusDays(3), 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);

        final StatTile tile = ActionStatsExtensions.tiles(s, List.of(ActionStatField.LAST_PERFORMED), 1).getFirst();

        assertThat(tile.date()).as("last-performed renders with the smaller date styling").isTrue();
        assertThat(tile.value()).as("value is the formatted date").isEqualTo("12 Jun 2025");
        assertThat(tile.sub()).as("sub is the relative label").isEqualTo("3 days ago");
        assertThat(tile.subNum()).as("the relative label carries a day count").isTrue();
    }

    @Test
    void tiles_trendTile_carriesTrendColourClass() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 5, 2, 0, 0, "—", 0, "—", 0);

        final StatTile tile = ActionStatsExtensions.tiles(s, List.of(ActionStatField.VS_LAST_MONTH), 1).getFirst();

        assertThat(tile.value()).as("upward month trend").isEqualTo("+3");
        assertThat(tile.valueClass()).as("upward trend is green").isEqualTo("text-green-600");
        assertThat(tile.sub()).as("sub carries the month context").isEqualTo("5 this month · 2 last month");
        assertThat(tile.subNum()).as("context carries locale-groupable counts").isTrue();
    }

    @Test
    void tiles_emptyFieldList_rendersNoTiles() {
        final ActionStats s = stats(2, 7, TODAY, TODAY, 0, 0, 0, 0, 0, 0, "—", 0, "—", 0);

        assertThat(ActionStatsExtensions.tiles(s, List.of(), 1))
            .as("no selected fields → no tiles")
            .isEmpty();
    }
}
