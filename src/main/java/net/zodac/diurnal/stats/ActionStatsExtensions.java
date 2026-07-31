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

import io.quarkus.qute.TemplateExtension;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import net.zodac.diurnal.time.DaySpan;
import net.zodac.diurnal.time.Durations;

/**
 * Derived labels, trends and predicates computed from an {@link ActionStats} record.
 *
 * <p>
 * This behaviour is deliberately held here rather than on the {@code ActionStats} record so that PITest can mutation-test it. PITest hot-swaps each
 * mutant into the running minion JVM via {@code Instrumentation.redefineClasses}, which the JVM refuses for a class carrying a {@code Record}
 * attribute — every record mutant failed with "class redefinition failed: attempted to change the Record attribute" (the "Minion exited abnormally
 * due to RUN_ERROR" lint warnings), leaving the logic untested. As methods on this plain class the same logic redefines cleanly and is fully mutated.
 * The template-facing methods are {@link TemplateExtension}s, so Qute still resolves {@code {s.monthTrend}}, {@code {s.currentStreakLabel}} etc.
 * against an {@code ActionStats} value.
 */
public final class ActionStatsExtensions {

    // Dates render at FULL width (the month spelled out); the front-end shortens "June" -> "Jun" -> and the
    // year to two digits only when the rendered label does not fit its tile - see Diurnal.fitFigures in app.js.
    private static final DateTimeFormatter DATE_FMT  = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FMT_NO_YEAR = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final double ZERO = 0.0;
    private static final String DAY_UNIT = "day";
    private static final String TIME_UNIT = "time";

    private ActionStatsExtensions() {

    }

    // ── Predicates (also used from StatsService) ──────────────────────────

    /**
     * Whether this action has any logged data (used to filter empty actions out of stats).
     *
     * @param stats the statistics to inspect
     * @return {@code true} if the action has at least one logged day
     */
    public static boolean hasData(final ActionStats stats) {
        return stats.totalDays() > 0;
    }

    /**
     * Whether this action was performed at least once in the current month.
     *
     * @param stats the statistics to inspect
     * @return {@code true} if the action was performed this month
     */
    public static boolean performedThisMonth(final ActionStats stats) {
        return stats.thisMonthCount() > 0;
    }

    // ── Stats-page tiles (user-configurable display) ──────────────────────

    /**
     * Builds the ordered list of Stats-page tiles for this action, one per {@link DisplayStat} the user has chosen to display, in the caller-supplied
     * order. Every value reuses the existing derived labels below, so the display preference never affects how the statistics are computed — only
     * which tiles are rendered, in what order, and (when the user has renamed a stat) under which caption.
     *
     * <p>
     * Called from {@code partials/stats-cards} as {@code {s.tiles(statsFields, decimalPlaces)}}.
     *
     * @param stats the statistics to render
     * @param fields the ordered stats the user has chosen to display, each with its caption
     * @param decimalPlaces the user's decimal-place preference (for the averages)
     * @return the ordered tiles to render
     */
    @TemplateExtension
    public static List<StatTile> tiles(final ActionStats stats, final List<DisplayStat> fields, final int decimalPlaces) {
        return fields.stream()
                .map(displayed -> tile(stats, displayed, decimalPlaces))
                .toList();
    }

    private static StatTile tile(final ActionStats stats, final DisplayStat displayed, final int decimalPlaces) {
        final String label = displayed.label();
        return switch (displayed.field()) {
            case CURRENT_STREAK -> durationTile(label, stats.currentStreak());
            case LONGEST_STREAK -> durationTile(label, stats.longestStreak());
            case CURRENT_GAP    -> durationTile(label, currentGapSpan(stats));
            case LONGEST_GAP    -> durationTile(label, stats.longestGap());
            case TOTAL_DAYS     -> numeric(label, Integer.toString(stats.totalDays()), totalDaysUnit(stats));
            case TOTAL_COUNT    -> numeric(label, Long.toString(stats.totalCount()), "all time");
            case WEEKLY_DAY_AVERAGE    -> numeric(label, weeklyDayAverage(stats, decimalPlaces), "days / week");
            case MONTHLY_DAY_AVERAGE   -> numeric(label, monthlyDayAverage(stats, decimalPlaces), "days / month");
            case WEEKLY_COUNT_AVERAGE  -> numeric(label, weeklyCountAverage(stats, decimalPlaces), "count / week");
            case MONTHLY_COUNT_AVERAGE -> numeric(label, monthlyCountAverage(stats, decimalPlaces), "count / month");
            case FIRST_PERFORMED -> labelTile(label, firstLabel(stats), sinceFirstLabel(stats));
            case LAST_PERFORMED -> labelTile(label, lastLabel(stats), sinceLabel(stats));
            case VS_LAST_MONTH  -> trendTile(label, monthTrend(stats), monthContext(stats), monthTrendClass(stats));
            case VS_LAST_YEAR   -> trendTile(label, yearTrend(stats), yearContext(stats), yearTrendClass(stats));
            // The high scores lead with WHEN the record was set; the count itself is the secondary caption.
            case BEST_MONTH     -> labelTile(label, stats.bestMonthLabel(), Durations.count(stats.bestMonthCount(), TIME_UNIT));
            case BEST_YEAR      -> labelTile(label, stats.bestYearLabel(), Durations.count(stats.bestYearCount(), TIME_UNIT));
        };
    }

    private static StatTile numeric(final String label, final String value, final String sub) {
        return new StatTile(label, value, sub, false, "text-ink", false);
    }

    private static StatTile labelTile(final String label, final String value, final String sub) {
        return new StatTile(label, value, sub, true, "text-ink", true);
    }

    private static StatTile trendTile(final String label, final String value, final String sub, final String valueClass) {
        return new StatTile(label, value, sub, true, valueClass, false);
    }

    // A day span keeps the prominent big-number styling while it reads as a plain day count; once it reaches a
    // calendar month the number is replaced by the condensed breakdown ("1 year, 2 months, 3 days"), which needs
    // the smaller two-line (date-flavoured) styling to fit - the exact day count then moves to the sub-caption.
    private static StatTile durationTile(final String label, final DaySpan span) {
        final int days = Durations.days(span);
        if (Durations.exceedsOneMonth(span)) {
            return labelTile(label, Durations.label(span), Durations.count(days, DAY_UNIT));
        }
        return numeric(label, Integer.toString(days), Durations.plural(days, DAY_UNIT));
    }

    // ── Date labels ───────────────────────────────────────────────────────
    // Dates render at full width and are never pre-abbreviated: the front-end shortens the month (and then the
    // year) only when the label does not fit its tile, so a wide viewport always sees the whole date.

    /**
     * The last-performed date formatted for display at full width ({@code "15 June 2026"}), or "Never" if the action was never logged.
     *
     * @param stats the statistics to inspect
     * @return the formatted last-performed date, or "Never"
     */
    @TemplateExtension
    public static String lastLabel(final ActionStats stats) {
        return stats.lastPerformed() == null ? "Never" : stats.lastPerformed().format(DATE_FMT);
    }

    /**
     * The first-performed date formatted for display at full width ({@code "15 June 2026"}), or "Never" if the action was never logged.
     *
     * @param stats the statistics to inspect
     * @return the formatted first-performed date, or "Never"
     */
    @TemplateExtension
    public static String firstLabel(final ActionStats stats) {
        return stats.firstPerformed() == null ? "Never" : stats.firstPerformed().format(DATE_FMT);
    }

    /**
     * The last-performed date for the dashboard "Latest" label: "Never" if never logged, "d MMM" (no year) when it falls in the current year, and the
     * full "d MMMM yyyy" only for an earlier year.
     *
     * @param stats the statistics to inspect
     * @return the formatted "Latest" label
     */
    @TemplateExtension
    public static String latestLabel(final ActionStats stats) {
        final LocalDate lastPerformed = stats.lastPerformed();
        if (lastPerformed == null) {
            return "Never";
        }
        return lastPerformed.getYear() == stats.today().getYear()
                ? lastPerformed.format(DATE_FMT_NO_YEAR)
                : lastPerformed.format(DATE_FMT);
    }

    /**
     * A relative label for the last-performed date: "Today", "Yesterday", or the elapsed span plus "ago" ({@code "5 days ago"},
     * {@code "1 year, 17 days ago"}). The span uses the same condensed {@link Durations} wording as the "Current gap" tile, which measures exactly
     * the same distance.
     *
     * @param stats the statistics to inspect
     * @return the relative last-performed label
     */
    @TemplateExtension
    public static String sinceLabel(final ActionStats stats) {
        return stats.lastPerformed() == null ? "—" : agoLabel(currentGapSpan(stats));
    }

    /**
     * A relative label for the first-performed date - how long the user has been doing this at all: "Today", "Yesterday", or the elapsed span plus
     * "ago" ({@code "5 days ago"}, {@code "1 year, 17 days ago"}).
     *
     * @param stats the statistics to inspect
     * @return the relative first-performed label
     */
    @TemplateExtension
    public static String sinceFirstLabel(final ActionStats stats) {
        final LocalDate firstPerformed = stats.firstPerformed();
        return firstPerformed == null ? "—" : agoLabel(new DaySpan(firstPerformed, stats.today()));
    }

    private static String agoLabel(final DaySpan elapsed) {
        final int days = Durations.days(elapsed);
        if (days == 0) {
            return "Today";
        }
        if (days == 1) {
            return "Yesterday";
        }
        return Durations.label(elapsed) + " ago";
    }

    // ── Averages ──────────────────────────────────────────────────────────
    // Two distinct things are averaged, so the labels must never be shortened back to a bare "weekly average":
    // the DAY averages count each active day once (how OFTEN the habit happens), the COUNT averages sum every
    // repeat on those days (how MUCH of it happens). Both are measured over the elapsed weeks/months since the
    // action was first performed, floored at one so a brand-new action reports its own total rather than a
    // divide-by-zero.

    /**
     * The average number of active days per week since the action was first performed, rendered to the given number of decimal places. A zero average
     * is simplified to a plain {@code "0"} (no trailing decimals) regardless of {@code decimalPlaces}.
     *
     * @param stats the statistics to inspect
     * @param decimalPlaces the number of decimal places to render (the user's preference)
     * @return the average active days per week, as a display string
     */
    @TemplateExtension
    public static String weeklyDayAverage(final ActionStats stats, final int decimalPlaces) {
        return average(stats, stats.totalDays(), ChronoUnit.WEEKS, decimalPlaces);
    }

    /**
     * The average number of active days per month since the action was first performed, rendered to the given number of decimal places.
     *
     * @param stats the statistics to inspect
     * @param decimalPlaces the number of decimal places to render (the user's preference)
     * @return the average active days per month, as a display string
     */
    @TemplateExtension
    public static String monthlyDayAverage(final ActionStats stats, final int decimalPlaces) {
        return average(stats, stats.totalDays(), ChronoUnit.MONTHS, decimalPlaces);
    }

    /**
     * The average total count per week since the action was first performed (every repeat on the same day included), rendered to the given number of
     * decimal places.
     *
     * @param stats the statistics to inspect
     * @param decimalPlaces the number of decimal places to render (the user's preference)
     * @return the average count per week, as a display string
     */
    @TemplateExtension
    public static String weeklyCountAverage(final ActionStats stats, final int decimalPlaces) {
        return average(stats, stats.totalCount(), ChronoUnit.WEEKS, decimalPlaces);
    }

    /**
     * The average total count per month since the action was first performed (every repeat on the same day included), rendered to the given number of
     * decimal places.
     *
     * @param stats the statistics to inspect
     * @param decimalPlaces the number of decimal places to render (the user's preference)
     * @return the average count per month, as a display string
     */
    @TemplateExtension
    public static String monthlyCountAverage(final ActionStats stats, final int decimalPlaces) {
        return average(stats, stats.totalCount(), ChronoUnit.MONTHS, decimalPlaces);
    }

    private static String average(final ActionStats stats, final long total, final ChronoUnit unit, final int decimalPlaces) {
        final LocalDate firstPerformed = stats.firstPerformed();
        if (firstPerformed == null) {
            return formatDecimal(0.0, decimalPlaces);
        }
        final long periods = Math.max(1L, unit.between(firstPerformed, stats.today()));
        return formatDecimal((double) total / periods, decimalPlaces);
    }

    /**
     * Formats a fractional stat value to {@code decimalPlaces} decimals, simplifying an exact zero to a plain {@code "0"} so an empty average reads
     * as {@code "0"} rather than {@code "0.0"}.
     *
     * @param value the value to format
     * @param decimalPlaces the number of decimal places to render
     * @return the formatted value
     */
    static String formatDecimal(final double value, final int decimalPlaces) {
        if (value == ZERO) {
            return "0";
        }

        final String format = "%." + decimalPlaces + "f";
        return String.format(Locale.ENGLISH, format, value);
    }

    // ── Streak/gap durations ──────────────────────────────────────────────
    // Every one of these spans is rendered through the shared Durations helper, so a long span always condenses
    // the same way ("412 days" -> "1 year, 1 month, 17 days") and a one-unit span never reads "1 months".

    /**
     * The run of days on which the action has NOT been performed since it last was: the day after the last logged date, up to and including today. An
     * action performed today (or never performed at all) has an empty run.
     *
     * <p>
     * Framed as the blank run rather than as "the distance from the last logged date to today" so that the current gap is measured exactly the same
     * way as {@link ActionStats#longestGap()} - the current gap becomes one of those the moment the action is performed again, and the two must not
     * disagree about their own length or wording.
     *
     * @param stats the statistics to inspect
     * @return the current gap
     */
    public static DaySpan currentGapSpan(final ActionStats stats) {
        final LocalDate lastPerformed = stats.lastPerformed();
        return lastPerformed == null
                ? new DaySpan(stats.today(), stats.today())
                : new DaySpan(lastPerformed.plusDays(1), stats.today().plusDays(1));
    }

    /**
     * The number of days since the action was last performed ({@code 0} when performed today, or never at all).
     *
     * @param stats the statistics to inspect
     * @return the current gap in days
     */
    @TemplateExtension
    public static int currentGap(final ActionStats stats) {
        return Durations.days(currentGapSpan(stats));
    }

    /**
     * The current streak as a condensed duration label, e.g. {@code "1 day"}, {@code "5 days"} or {@code "2 months, 3 days"}.
     *
     * @param stats the statistics to inspect
     * @return the current-streak label
     */
    @TemplateExtension
    public static String currentStreakLabel(final ActionStats stats) {
        return Durations.label(stats.currentStreak());
    }

    /**
     * The longest streak as a condensed duration label, e.g. {@code "1 day"}, {@code "5 days"} or {@code "2 months, 3 days"}.
     *
     * @param stats the statistics to inspect
     * @return the longest-streak label
     */
    @TemplateExtension
    public static String longestStreakLabel(final ActionStats stats) {
        return Durations.label(stats.longestStreak());
    }

    /**
     * The current gap as a condensed duration label, e.g. {@code "1 day"}, {@code "5 days"} or {@code "2 months, 3 days"}.
     *
     * @param stats the statistics to inspect
     * @return the current-gap label
     */
    @TemplateExtension
    public static String currentGapLabel(final ActionStats stats) {
        return Durations.label(currentGapSpan(stats));
    }

    /**
     * The longest gap as a condensed duration label, e.g. {@code "1 day"}, {@code "5 days"} or {@code "2 months, 3 days"}.
     *
     * @param stats the statistics to inspect
     * @return the longest-gap label
     */
    @TemplateExtension
    public static String longestGapLabel(final ActionStats stats) {
        return Durations.label(stats.longestGap());
    }

    /**
     * The unit word ({@code "day"}/{@code "days"}) matching the current-streak count.
     *
     * @param stats the statistics to inspect
     * @return the current-streak unit word
     */
    @TemplateExtension
    public static String currentStreakUnit(final ActionStats stats) {
        return Durations.plural(Durations.days(stats.currentStreak()), DAY_UNIT);
    }

    /**
     * The unit word ({@code "day"}/{@code "days"}) matching the longest-streak count.
     *
     * @param stats the statistics to inspect
     * @return the longest-streak unit word
     */
    @TemplateExtension
    public static String longestStreakUnit(final ActionStats stats) {
        return Durations.plural(Durations.days(stats.longestStreak()), DAY_UNIT);
    }

    /**
     * The unit word ({@code "day"}/{@code "days"}) matching the longest-gap count.
     *
     * @param stats the statistics to inspect
     * @return the longest-gap unit word
     */
    @TemplateExtension
    public static String longestGapUnit(final ActionStats stats) {
        return Durations.plural(Durations.days(stats.longestGap()), DAY_UNIT);
    }

    /**
     * The unit phrase ({@code "unique day"}/{@code "unique days"}) matching the total-days count.
     *
     * @param stats the statistics to inspect
     * @return the total-days unit phrase
     */
    @TemplateExtension
    public static String totalDaysUnit(final ActionStats stats) {
        return Durations.plural(stats.totalDays(), "unique day");
    }

    // ── Comparative helpers ───────────────────────────────────────────────

    /**
     * This month's count relative to last month's, as a signed trend label.
     *
     * @param stats the statistics to inspect
     * @return the month trend label
     */
    @TemplateExtension
    public static String monthTrend(final ActionStats stats) {
        return trend(stats.thisMonthCount(), stats.lastMonthCount());
    }

    /**
     * The CSS colour class matching {@link #monthTrend(ActionStats)} (up/down/flat).
     *
     * @param stats the statistics to inspect
     * @return the month trend colour class
     */
    @TemplateExtension
    public static String monthTrendClass(final ActionStats stats) {
        return trendClass(stats.thisMonthCount(), stats.lastMonthCount());
    }

    /**
     * A "{@code X this month · Y last month}" context string.
     *
     * @param stats the statistics to inspect
     * @return the month context string
     */
    @TemplateExtension
    public static String monthContext(final ActionStats stats) {
        return stats.thisMonthCount() + " this month · " + stats.lastMonthCount() + " last month";
    }

    /**
     * A "{@code X this month}" context string (just the current month, no last-month comparison).
     *
     * @param stats the statistics to inspect
     * @return the this-month context string
     */
    @TemplateExtension
    public static String thisMonthContext(final ActionStats stats) {
        return stats.thisMonthCount() + " this month";
    }

    /**
     * This year's count relative to last year's, as a signed trend label.
     *
     * @param stats the statistics to inspect
     * @return the year trend label
     */
    @TemplateExtension
    public static String yearTrend(final ActionStats stats) {
        return trend(stats.thisYearCount(), stats.lastYearCount());
    }

    /**
     * The CSS colour class matching {@link #yearTrend(ActionStats)} (up/down/flat).
     *
     * @param stats the statistics to inspect
     * @return the year trend colour class
     */
    @TemplateExtension
    public static String yearTrendClass(final ActionStats stats) {
        return trendClass(stats.thisYearCount(), stats.lastYearCount());
    }

    /**
     * A "{@code X this year · Y last year}" context string.
     *
     * @param stats the statistics to inspect
     * @return the year context string
     */
    @TemplateExtension
    public static String yearContext(final ActionStats stats) {
        return stats.thisYearCount() + " this year · " + stats.lastYearCount() + " last year";
    }

    // ── Private ───────────────────────────────────────────────────────────

    private static String trend(final long current, final long previous) {
        if (current == 0 && previous == 0) {
            return "—";
        }
        if (previous == 0) {
            return "+" + current;
        }
        final long diff = current - previous;
        if (diff > 0) {
            return "+" + diff;
        }
        if (diff < 0) {
            return Long.toString(diff);
        }
        return "=";
    }

    private static String trendClass(final long current, final long previous) {
        final long diff = current - previous;
        if (diff > 0) {
            return "text-green-600";
        }
        if (diff < 0) {
            return "text-red-500";
        }
        return "text-gray-400";
    }
}
