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
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.stats.FrequencyCharts.ChartedAction;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class FrequencyChartsTest {

    private static final UUID RUNNING_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID YOGA_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final ChartedAction RUNNING = new ChartedAction(RUNNING_ID, "Running", "#64748b");
    private static final ChartedAction YOGA = new ChartedAction(YOGA_ID, "Yoga", "#6366f1");
    private static final LocalDate JULY_2026 = LocalDate.of(2026, 7, 1);
    private static final LocalDate YEAR_2026 = LocalDate.of(2026, 1, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 23);

    private static FrequencyChart month(final Map<UUID, Map<Integer, Long>> counts, final LocalDate anchor, final @Nullable LocalDate earliest) {
        return FrequencyCharts.build(List.of(RUNNING), FrequencyPeriod.MONTH, anchor, counts, TODAY, earliest);
    }

    private static FrequencyChart year(final Map<UUID, Map<Integer, Long>> counts, final LocalDate anchor, final @Nullable LocalDate earliest) {
        return FrequencyCharts.build(List.of(RUNNING), FrequencyPeriod.YEAR, anchor, counts, TODAY, earliest);
    }

    private static Map<UUID, Map<Integer, Long>> running(final Map<Integer, Long> counts) {
        return Map.of(RUNNING_ID, counts);
    }

    // ── Shape ───────────────────────────────────────────────────────────────

    @Test
    void build_monthWindow_hasOneColumnPerDayIncludingTheEmptyOnes() {
        final FrequencyChart chart = month(running(Map.of(3, 4L)), JULY_2026, JULY_2026);
        assertThat(chart.slots())
            .as("a 31-day month should draw 31 columns, blanks included, so the axis stays evenly spaced")
            .hasSize(31);
        assertThat(chart.slots().get(0).bars().getFirst().count())
            .as("an unlogged day should carry a zero count")
            .isZero();
        assertThat(chart.slots().get(2).bars().getFirst().count())
            .as("the logged day's count should be carried through")
            .isEqualTo(4L);
    }

    @Test
    void build_monthWindow_columnsAreCaptionedByDayAndSpelledOutForHover() {
        final FrequencyChart chart = month(running(Map.of(3, 4L)), JULY_2026, JULY_2026);
        assertThat(chart.slots().get(2).label())
            .as("the axis caption should be the day of the month")
            .isEqualTo("3");
        assertThat(chart.slots().get(2).fullLabel())
            .as("the hover label should spell the day out in full")
            .isEqualTo("3 July 2026");
    }

    @Test
    void build_shortMonth_drawsOnlyItsOwnDays() {
        assertThat(month(Map.of(), LocalDate.of(2026, 2, 1), JULY_2026).slots())
            .as("a non-leap February should draw 28 columns")
            .hasSize(28);
    }

    @Test
    void build_leapFebruary_keepsTheLeapDay() {
        assertThat(month(Map.of(), LocalDate.of(2024, 2, 1), JULY_2026).slots())
            .as("a leap February should draw 29 columns")
            .hasSize(29);
    }

    @Test
    void build_yearWindow_hasOneColumnPerMonthCaptionedByAbbreviation() {
        final FrequencyChart chart = year(running(Map.of(7, 9L)), YEAR_2026, YEAR_2026);
        assertThat(chart.slots())
            .as("a year should draw 12 columns")
            .hasSize(12);
        assertThat(chart.slots().get(6).label())
            .as("the axis caption should be the abbreviated month name")
            .isEqualTo("Jul");
        assertThat(chart.slots().get(6).fullLabel())
            .as("the hover label should spell the month out in full")
            .isEqualTo("July 2026");
        assertThat(chart.slots().get(6).bars().getFirst().count())
            .as("unexpected value")
            .isEqualTo(9L);
    }

    // ── Totals ──────────────────────────────────────────────────────────────

    @Test
    void build_totalAndPeak_areSummedAcrossTheWindow() {
        final FrequencyChart chart = month(running(Map.of(1, 2L, 5, 9L, 20, 4L)), JULY_2026, JULY_2026);
        assertThat(chart.total())
            .as("the total should sum every slot of the window")
            .isEqualTo(15L);
        assertThat(chart.peak())
            .as("the peak should be the tallest bar's count")
            .isEqualTo(9L);
    }

    @Test
    void build_lastSlotOfTheWindow_isCounted() {
        // The final day/month of a window is the one an off-by-one in the accumulation would drop, and it
        // would go unnoticed everywhere else: the bar would still be drawn, only flat and uncounted.
        final FrequencyChart chart = month(running(Map.of(31, 6L)), JULY_2026, JULY_2026);
        assertThat(chart.total())
            .as("the last day of the month should be included in the total")
            .isEqualTo(6L);
        assertThat(chart.peak())
            .as("the last day of the month should be able to set the peak")
            .isEqualTo(6L);
        assertThat(chart.slots().getLast().bars().getFirst().heightPercent())
            .as("the last day's bar should be scaled, not flat")
            .isEqualTo(100);
        assertThat(chart.series().getFirst().total())
            .as("the legend's per-action total is accumulated separately, so it needs the same last-slot guard")
            .isEqualTo(6L);
    }

    @Test
    void build_lastMonthOfTheYear_isCounted() {
        final FrequencyChart chart = year(running(Map.of(12, 8L)), YEAR_2026, YEAR_2026);
        assertThat(chart.total())
            .as("December should be included in the year's total")
            .isEqualTo(8L);
        assertThat(chart.peak())
            .as("December should be able to set the year's peak")
            .isEqualTo(8L);
    }

    @Test
    void build_emptyWindow_reportsNothingLogged() {
        final FrequencyChart chart = month(Map.of(), JULY_2026, JULY_2026);
        assertThat(chart.total())
            .as("an empty window should total zero")
            .isZero();
        assertThat(chart.peak())
            .as("an empty window has no peak, which is what the template's empty state keys on")
            .isZero();
        assertThat(chart.slots())
            .as("every bar of an empty window should be flat")
            .allMatch(slot -> slot.bars().getFirst().heightPercent() == 0);
    }

    @Test
    void build_labelsTheWindowAndItsNeighbours() {
        final FrequencyChart chart = month(Map.of(), JULY_2026, JULY_2026);
        assertThat(chart.periodKey())
            .as("unexpected value")
            .isEqualTo("2026-07");
        assertThat(chart.periodLabel())
            .as("unexpected value")
            .isEqualTo("July 2026");
        assertThat(chart.previousKey())
            .as("unexpected value")
            .isEqualTo("2026-06");
        assertThat(chart.nextKey())
            .as("unexpected value")
            .isEqualTo("2026-08");
        assertThat(chart.period())
            .as("unexpected value")
            .isEqualTo(FrequencyPeriod.MONTH);
    }

    // ── Series ──────────────────────────────────────────────────────────────

    @Test
    void build_singleAction_isTheOnlySeriesAndIsNotRemovable() {
        final FrequencyChart chart = month(running(Map.of(1, 3L)), JULY_2026, JULY_2026);
        assertThat(chart.series())
            .as("one charted action should yield one series")
            .hasSize(1);
        final FrequencySeries series = chart.series().getFirst();
        assertThat(series.actionId())
            .as("unexpected value")
            .isEqualTo(RUNNING_ID);
        assertThat(series.actionName())
            .as("unexpected value")
            .isEqualTo("Running");
        assertThat(series.actionColour())
            .as("the bars are painted from the action's own colour")
            .isEqualTo("#64748b");
        assertThat(series.total())
            .as("the series total should sum only its own action")
            .isEqualTo(3L);
        assertThat(series.removable())
            .as("the action the graph was opened from can never be dropped")
            .isFalse();
    }

    @Test
    void build_comparedAction_isSecondSeriesAndIsRemovable() {
        final FrequencyChart chart = FrequencyCharts.build(List.of(RUNNING, YOGA), FrequencyPeriod.MONTH, JULY_2026,
            Map.of(RUNNING_ID, Map.of(1, 3L), YOGA_ID, Map.of(1, 5L)), TODAY, JULY_2026);
        assertThat(chart.series().stream().map(FrequencySeries::actionName).toList())
            .as("the legend should keep the order the comparison was built in")
            .containsExactly("Running", "Yoga");
        assertThat(chart.series().get(1).removable())
            .as("a compared action can be dropped again")
            .isTrue();
        assertThat(chart.series().get(1).total())
            .as("each series totals only its own action")
            .isEqualTo(5L);
        assertThat(chart.total())
            .as("the chart total spans every charted action")
            .isEqualTo(8L);
    }

    @Test
    void build_everyColumnCarriesOneBarPerChartedAction() {
        final FrequencyChart chart = FrequencyCharts.build(List.of(RUNNING, YOGA), FrequencyPeriod.MONTH, JULY_2026,
            Map.of(RUNNING_ID, Map.of(1, 3L)), TODAY, JULY_2026);
        assertThat(chart.slots())
            .as("every column must carry a bar for every action, so the groups stay aligned across the axis")
            .allMatch(slot -> slot.bars().size() == 2);
        assertThat(chart.slots().getFirst().bars().get(1).count())
            .as("an action with nothing in the window still gets a zero bar, not a missing one")
            .isZero();
        assertThat(chart.slots().getFirst().bars().get(1).actionName())
            .as("each bar names its own action, which is what the column's hover bubble reads")
            .isEqualTo("Yoga");
    }

    @Test
    void build_barsOfEveryActionShareOnePeak() {
        // The whole point of charting two actions together is comparing them, so a bar twice the height
        // of another must mean twice the count - which only holds if both scale against the same peak.
        final FrequencyChart chart = FrequencyCharts.build(List.of(RUNNING, YOGA), FrequencyPeriod.MONTH, JULY_2026,
            Map.of(RUNNING_ID, Map.of(1, 5L), YOGA_ID, Map.of(1, 10L)), TODAY, JULY_2026);
        assertThat(chart.slots().getFirst().bars().getFirst().heightPercent())
            .as("half the chart's peak should draw at half height")
            .isEqualTo(50);
        assertThat(chart.slots().getFirst().bars().get(1).heightPercent())
            .as("the chart's peak should fill the plot")
            .isEqualTo(100);
    }

    @Test
    void build_comparedActionCanSetTheChartPeak() {
        final FrequencyChart chart = FrequencyCharts.build(List.of(RUNNING, YOGA), FrequencyPeriod.MONTH, JULY_2026,
            Map.of(RUNNING_ID, Map.of(1, 2L), YOGA_ID, Map.of(4, 9L)), TODAY, JULY_2026);
        assertThat(chart.peak())
            .as("the peak spans every charted action, not just the first")
            .isEqualTo(9L);
    }

    @Test
    void maxSeries_isThree() {
        assertThat(FrequencyCharts.MAX_SERIES)
            .as("three actions is what stays legible on a 31-column month window")
            .isEqualTo(3);
    }

    // ── Navigation bounds ───────────────────────────────────────────────────

    @Test
    void build_currentMonth_cannotStepForward() {
        final FrequencyChart chart = month(Map.of(), JULY_2026, LocalDate.of(2026, 1, 1));
        assertThat(chart.hasNext())
            .as("the window containing today is the last one worth showing")
            .isFalse();
        assertThat(chart.hasPrevious())
            .as("there are earlier logged months to step back to")
            .isTrue();
    }

    @Test
    void build_pastMonth_canStepForward() {
        assertThat(month(Map.of(), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 1, 1)).hasNext())
            .as("a past window should be able to step towards today")
            .isTrue();
    }

    @Test
    void build_firstLoggedMonth_cannotStepBack() {
        assertThat(month(Map.of(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1)).hasPrevious())
            .as("there is nothing logged before the first logged month")
            .isFalse();
    }

    @Test
    void build_neverLoggedAction_cannotStepBack() {
        assertThat(month(Map.of(), JULY_2026, null).hasPrevious())
            .as("an action with no logs at all has no earlier window")
            .isFalse();
    }

    @Test
    void build_yearWindow_boundsAgainstTheLoggedAndCurrentYears() {
        assertThat(year(Map.of(), YEAR_2026, LocalDate.of(2024, 3, 1)).hasPrevious())
            .as("2025 still holds logs, so stepping back is offered")
            .isTrue();
        assertThat(year(Map.of(), YEAR_2026, YEAR_2026).hasPrevious())
            .as("nothing is logged before 2026, so stepping back is not offered")
            .isFalse();
        assertThat(year(Map.of(), YEAR_2026, YEAR_2026).hasNext())
            .as("2027 is in the future")
            .isFalse();
        assertThat(year(Map.of(), LocalDate.of(2025, 1, 1), LocalDate.of(2024, 1, 1)).hasNext())
            .as("2026 is not in the future")
            .isTrue();
    }

    // ── heightPercent ───────────────────────────────────────────────────────

    @Test
    void heightPercent_scalesAgainstThePeak() {
        assertThat(FrequencyCharts.heightPercent(10L, 10L))
            .as("the tallest bar should fill the plot")
            .isEqualTo(100);
        assertThat(FrequencyCharts.heightPercent(5L, 10L))
            .as("unexpected value")
            .isEqualTo(50);
        assertThat(FrequencyCharts.heightPercent(1L, 3L))
            .as("the percentage should be rounded, not truncated")
            .isEqualTo(33);
        assertThat(FrequencyCharts.heightPercent(2L, 3L))
            .as("the percentage should be rounded, not truncated")
            .isEqualTo(67);
    }

    @Test
    void heightPercent_emptySlot_drawsNothing() {
        assertThat(FrequencyCharts.heightPercent(0L, 10L))
            .as("an unlogged slot should draw no bar at all")
            .isZero();
        assertThat(FrequencyCharts.heightPercent(-1L, 10L))
            .as("a nonsensical negative count should draw no bar rather than an inverted one")
            .isZero();
    }

    @Test
    void heightPercent_noPeak_drawsNothing() {
        assertThat(FrequencyCharts.heightPercent(4L, 0L))
            .as("with no peak to scale against there is nothing to draw")
            .isZero();
    }

    @Test
    void heightPercent_tinyCountAgainstTallPeak_staysVisible() {
        assertThat(FrequencyCharts.heightPercent(1L, 999L))
            .as("a logged slot must never round away to an invisible sliver")
            .isEqualTo(3);
    }

    // ── tipAlign ────────────────────────────────────────────────────────────

    @Test
    void tipAlign_edgeSlots_growInward() {
        assertThat(FrequencyCharts.tipAlign(1, 31))
            .as("the first column's bubble should grow rightward")
            .isEqualTo("left");
        assertThat(FrequencyCharts.tipAlign(4, 31))
            .as("unexpected value")
            .isEqualTo("left");
        assertThat(FrequencyCharts.tipAlign(31, 31))
            .as("the last column's bubble should grow leftward")
            .isEqualTo("right");
        assertThat(FrequencyCharts.tipAlign(28, 31))
            .as("unexpected value")
            .isEqualTo("right");
    }

    @Test
    void tipAlign_middleSlots_areCentred() {
        assertThat(FrequencyCharts.tipAlign(5, 31))
            .as("unexpected value")
            .isEqualTo("center");
        assertThat(FrequencyCharts.tipAlign(27, 31))
            .as("unexpected value")
            .isEqualTo("center");
    }

    @Test
    void build_appliesTheEdgeAlignmentsAcrossTheWindow() {
        final List<String> expected = List.of(
            "left",
            "left",
            "left",
            "left",
            "center",
            "center",
            "center",
            "center",
            "right",
            "right",
            "right",
            "right");
        assertThat(year(Map.of(), YEAR_2026, YEAR_2026).slots().stream().map(FrequencySlot::tipAlign).toList())
            .as("the first and last four months should anchor their bubbles inward")
            .containsExactlyElementsOf(expected);
    }
}
