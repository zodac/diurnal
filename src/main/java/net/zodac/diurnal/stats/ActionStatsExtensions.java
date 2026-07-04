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
import java.util.Locale;

/**
 * Derived labels, trends and predicates computed from an {@link ActionStats} record.
 *
 * <p>This behaviour is deliberately held here rather than on the {@code ActionStats} record so that
 * PITest can mutation-test it. PITest hot-swaps each mutant into the running minion JVM via
 * {@code Instrumentation.redefineClasses}, which the JVM refuses for a class carrying a
 * {@code Record} attribute — every record mutant failed with "class redefinition failed: attempted
 * to change the Record attribute" (the "Minion exited abnormally due to RUN_ERROR" lint warnings),
 * leaving the logic untested. As methods on this plain class the same logic redefines cleanly and is
 * fully mutated. The template-facing methods are {@link TemplateExtension}s, so Qute still resolves
 * {@code {s.monthTrend}}, {@code {s.currentStreakLabel}} etc. against an {@code ActionStats} value.
 */
public final class ActionStatsExtensions {

    private static final DateTimeFormatter DATE_FMT  =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FMT_NO_YEAR =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private ActionStatsExtensions() {
        // Static helpers only.
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

    // ── Date labels ───────────────────────────────────────────────────────

    /**
     * The last-performed date formatted for display, or "Never" if the action was never logged.
     *
     * @param stats the statistics to inspect
     * @return the formatted last-performed date, or "Never"
     */
    @TemplateExtension
    public static String lastLabel(final ActionStats stats) {
        return stats.lastPerformed() == null ? "Never" : stats.lastPerformed().format(DATE_FMT);
    }

    /**
     * The last-performed date for the dashboard "Latest" label: "Never" if never logged, "d MMM" (no
     * year) when it falls in the current year, and the full "d MMM yyyy" only for an earlier year.
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
     * A relative label for the last-performed date: "Today", "Yesterday" or "N days ago".
     *
     * @param stats the statistics to inspect
     * @return the relative last-performed label
     */
    @TemplateExtension
    public static String sinceLabel(final ActionStats stats) {
        final LocalDate lastPerformed = stats.lastPerformed();
        if (lastPerformed == null) {
            return "—";
        }
        final long days = ChronoUnit.DAYS.between(lastPerformed, stats.today());
        if (days == 0) {
            return "Today";
        }
        if (days == 1) {
            return "Yesterday";
        }
        return days + " days ago";
    }

    /**
     * The average number of active days per week since the action was first performed, rendered to
     * the given number of decimal places. A zero average is simplified to a plain {@code "0"} (no
     * trailing decimals) regardless of {@code decimalPlaces}.
     *
     * @param stats         the statistics to inspect
     * @param decimalPlaces the number of decimal places to render (the user's preference)
     * @return the weekly average as a display string
     */
    @TemplateExtension
    public static String weeklyAverage(final ActionStats stats, final int decimalPlaces) {
        final LocalDate firstPerformed = stats.firstPerformed();
        if (firstPerformed == null) {
            return formatDecimal(0.0, decimalPlaces);
        }
        final long weeks = Math.max(1L, ChronoUnit.WEEKS.between(firstPerformed, stats.today()));
        return formatDecimal((double) stats.totalDays() / weeks, decimalPlaces);
    }

    /**
     * Formats a fractional stat value to {@code decimalPlaces} decimals, simplifying an exact zero to
     * a plain {@code "0"} so an empty average reads as {@code "0"} rather than {@code "0.0"}.
     *
     * @param value         the value to format
     * @param decimalPlaces the number of decimal places to render
     * @return the formatted value
     */
    static String formatDecimal(final double value, final int decimalPlaces) {
        if (value == 0.0) {
            return "0";
        }
        return String.format(Locale.ENGLISH, "%." + decimalPlaces + "f", value);
    }

    // ── Singular-aware day/streak labels ──────────────────────────────────

    /**
     * The current streak as a singular-aware label, e.g. {@code "1 day"} or {@code "5 days"}.
     *
     * @param stats the statistics to inspect
     * @return the current-streak label
     */
    @TemplateExtension
    public static String currentStreakLabel(final ActionStats stats) {
        return stats.currentStreak() + " " + currentStreakUnit(stats);
    }

    /**
     * The longest streak as a singular-aware label, e.g. {@code "1 day"} or {@code "5 days"}.
     *
     * @param stats the statistics to inspect
     * @return the longest-streak label
     */
    @TemplateExtension
    public static String longestStreakLabel(final ActionStats stats) {
        return stats.longestStreak() + " " + longestStreakUnit(stats);
    }

    /**
     * The unit word ({@code "day"}/{@code "days"}) matching the current-streak count.
     *
     * @param stats the statistics to inspect
     * @return the current-streak unit word
     */
    @TemplateExtension
    public static String currentStreakUnit(final ActionStats stats) {
        return plural(stats.currentStreak(), "day");
    }

    /**
     * The unit word ({@code "day"}/{@code "days"}) matching the longest-streak count.
     *
     * @param stats the statistics to inspect
     * @return the longest-streak unit word
     */
    @TemplateExtension
    public static String longestStreakUnit(final ActionStats stats) {
        return plural(stats.longestStreak(), "day");
    }

    /**
     * The longest gap as a singular-aware label, e.g. {@code "1 day"} or {@code "5 days"}.
     *
     * @param stats the statistics to inspect
     * @return the longest-gap label
     */
    @TemplateExtension
    public static String longestGapLabel(final ActionStats stats) {
        return stats.longestGap() + " " + longestGapUnit(stats);
    }

    /**
     * The unit word ({@code "day"}/{@code "days"}) matching the longest-gap count.
     *
     * @param stats the statistics to inspect
     * @return the longest-gap unit word
     */
    @TemplateExtension
    public static String longestGapUnit(final ActionStats stats) {
        return plural(stats.longestGap(), "day");
    }

    /**
     * The unit phrase ({@code "distinct day"}/{@code "distinct days"}) matching the total-days count.
     *
     * @param stats the statistics to inspect
     * @return the total-days unit phrase
     */
    @TemplateExtension
    public static String totalDaysUnit(final ActionStats stats) {
        return plural(stats.totalDays(), "distinct day");
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

    private static String plural(final long count, final String unit) {
        return count == 1 ? unit : unit + "s";
    }

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
