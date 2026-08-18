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
import org.jspecify.annotations.Nullable;

/**
 * Derived labels, trends and predicates computed from an {@link SubjectStats} record.
 *
 * <p>
 * This behaviour is deliberately held here rather than on the {@code SubjectStats} record so that PITest can mutation-test it. PITest hot-swaps each
 * mutant into the running minion JVM via {@code Instrumentation.redefineClasses}, which the JVM refuses for a class carrying a {@code Record}
 * attribute — every record mutant failed with "class redefinition failed: attempted to change the Record attribute" (the "Minion exited abnormally
 * due to RUN_ERROR" lint warnings), leaving the logic untested. As methods on this plain class the same logic redefines cleanly and is fully mutated.
 * The template-facing methods are {@link TemplateExtension}s, so Qute still resolves {@code {s.monthTrend}}, {@code {s.latestLabel}} etc.
 * against an {@code SubjectStats} value.
 */
public final class SubjectStatsExtensions {

    // Dates render at FULL width (the month spelled out); the front-end shortens "June" -> "Jun" -> and the
    // year to two digits only when the rendered label does not fit its tile - see Diurnal.fitFigures in app.js.
    private static final DateTimeFormatter DATE_FMT  = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FMT_NO_YEAR = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final double ZERO = 0.0;
    private static final String RANGE_SEPARATOR = " – ";

    private SubjectStatsExtensions() {

    }

    // ── Predicates (also used from StatsService) ──────────────────────────

    /**
     * Whether this action has any logged data (used to filter empty actions out of stats).
     *
     * @param stats the statistics to inspect
     * @return {@code true} if the action has at least one logged day
     */
    public static boolean hasData(final SubjectStats stats) {
        return stats.totalDays() > 0;
    }

    /**
     * Whether this action was performed at least once in the current month.
     *
     * @param stats the statistics to inspect
     * @return {@code true} if the action was performed this month
     */
    public static boolean performedThisMonth(final SubjectStats stats) {
        return stats.thisMonthCount() > 0L;
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
    public static List<StatTile> tiles(final SubjectStats stats, final List<DisplayStat> fields, final int decimalPlaces) {
        return fields.stream()
                .map(displayed -> tile(stats, displayed, decimalPlaces))
                .toList();
    }

    // One exhaustive arm per StatField, so its length is the size of the catalogue rather than complexity. Splitting it would need a second switch
    // over the same enum, which must either carry an unreachable `default -> throw` (a mutant no test can kill, and PITest is held at 100%) or a
    // reachable one that silently renders the next field added as whichever case it absorbed. The flat table is the safer form.
    @SuppressWarnings("OverlyLongMethod")
    private static StatTile tile(final SubjectStats stats, final DisplayStat displayed, final int decimalPlaces) {
        final String label = displayed.label();
        final String key = displayed.field().key();
        // A rename is "the user's own text" the moment it differs from the catalogue's own default wording (the
        // same equality StatField.customLabelFor already applies on write/read) - only the DEFAULT case is ever
        // translated, per I18N.md's "third bucket" notes (a Java-side AppMessages call is not locale-aware here,
        // so the actual translation lookup happens template-side, keyed on this StatTile's key).
        final boolean labelIsCustom = !label.equals(displayed.field().label());
        return switch (displayed.field()) {
            case CURRENT_STREAK -> durationTile(key, label, labelIsCustom, stats.currentStreak(), true);
            case LONGEST_STREAK -> durationTile(key, label, labelIsCustom, stats.longestStreak(), false);
            case CURRENT_GAP    -> durationTile(key, label, labelIsCustom, currentGapSpan(stats), true);
            case LONGEST_GAP    -> durationTile(key, label, labelIsCustom, stats.longestGap(), false);
            case TOTAL_DAYS     -> numeric(key, label, labelIsCustom, Integer.toString(stats.totalDays()), stats.totalDays());
            case TOTAL_COUNT    -> numeric(key, label, labelIsCustom, Long.toString(stats.totalCount()), 0L);
            case WEEKLY_DAY_AVERAGE    -> numeric(key, label, labelIsCustom, weeklyDayAverage(stats, decimalPlaces), 0L);
            case MONTHLY_DAY_AVERAGE   -> numeric(key, label, labelIsCustom, monthlyDayAverage(stats, decimalPlaces), 0L);
            case WEEKLY_COUNT_AVERAGE  -> numeric(key, label, labelIsCustom, weeklyCountAverage(stats, decimalPlaces), 0L);
            case MONTHLY_COUNT_AVERAGE -> numeric(key, label, labelIsCustom, monthlyCountAverage(stats, decimalPlaces), 0L);
            case FIRST_PERFORMED ->
                sinceTile(key, label, labelIsCustom, firstLabel(stats),
                    stats.firstPerformed() == null ? null : new DaySpan(stats.firstPerformed(), stats.today()));
            case LAST_PERFORMED ->
                sinceTile(key, label, labelIsCustom, lastLabel(stats), stats.lastPerformed() == null ? null : currentGapSpan(stats));
            case VS_LAST_MONTH  ->
                trendTile(key, label, labelIsCustom, monthTrend(stats), monthTrendClass(stats), stats.thisMonthCount(), stats.lastMonthCount());
            case VS_LAST_YEAR   ->
                trendTile(key, label, labelIsCustom, yearTrend(stats), yearTrendClass(stats), stats.thisYearCount(), stats.lastYearCount());
            // The high scores lead with WHEN the record was set; the count itself is the secondary caption.
            case BEST_MONTH     -> recordTile(key, label, labelIsCustom, stats.bestMonthLabel(), stats.bestMonthCount());
            case BEST_YEAR      -> recordTile(key, label, labelIsCustom, stats.bestYearLabel(), stats.bestYearCount());
        };
    }

    private static StatTile numeric(final String key, final String label, final boolean labelIsCustom, final String value,
        final long subCount1) {
        return new StatTile(key, label, labelIsCustom, value, "", false, "text-ink", false, subCount1, 0L, 0, "");
    }

    // FIRST_PERFORMED / LAST_PERFORMED: the sub-caption is "Today"/"Yesterday"/"<elapsed> ago", or a dash when
    // never performed - see partials/stats-cards.html's {#switch tile.key}, which is the only place that can
    // resolve those words in the viewer's own language (a direct Java call to AppMessages always returns the
    // English default; see AppMessages' own class Javadoc). -1 (never performed) rather than a boolean-plus-count
    // pair, since every legitimate day count is >= 0. LAST_PERFORMED's caller passes the SAME span as the
    // CURRENT_GAP tile (currentGapSpan, shifted a day from the naive first/today range) so the two report
    // identically for the same distance.
    private static StatTile sinceTile(final String key, final String label, final boolean labelIsCustom, final String value,
        final @Nullable DaySpan span) {
        if (span == null) {
            return new StatTile(key, label, labelIsCustom, value, "", true, "text-ink", true, 0L, 0L, -1, "");
        }
        final int daysAgo = Durations.days(span);
        final String elapsed = daysAgo >= 2 ? Durations.label(span) : "";
        return new StatTile(key, label, labelIsCustom, value, "", true, "text-ink", true, 0L, 0L, daysAgo, elapsed);
    }

    private static StatTile recordTile(final String key, final String label, final boolean labelIsCustom, final String value,
        final long subCount1) {
        return new StatTile(key, label, labelIsCustom, value, "", true, "text-ink", true, subCount1, 0L, 0, "");
    }

    private static StatTile trendTile(final String key, final String label, final boolean labelIsCustom, final String value,
        final String valueClass, final long subCount1, final long subCount2) {
        return new StatTile(key, label, labelIsCustom, value, "", true, valueClass, false, subCount1, subCount2, 0, "");
    }

    // Every duration tile leads with the figure AND its unit ("1 day", "5 days", "1 year, 2 months, 3 days"),
    // so the four streak/gap tiles read identically however long the run is - a one-day run must not render a
    // bare "1" while a longer one spells its units out. The sub-caption then carries the run's actual dates,
    // which is the one piece of information the condensed duration drops. A run that is still going has no end
    // date to show, a one-day run needs only the single date, and an empty run has none. Only the still-going
    // case needs a translated word ("since") in front of the date - a closed range is dates and a punctuation
    // separator, neither of which is English-specific - so only that word moves to the template; see
    // partials/stats-cards.html's {#switch tile.key}.
    private static StatTile durationTile(final String key, final String label, final boolean labelIsCustom, final DaySpan span,
        final boolean ongoing) {
        return new StatTile(key, label, labelIsCustom, Durations.label(span), rangeLabel(span, ongoing), false, "text-ink", true, 0L, 0L, 0, "");
    }

    private static String rangeLabel(final DaySpan span, final boolean ongoing) {
        if (Durations.days(span) == 0) {
            return "";
        }

        final String start = span.start().format(DATE_FMT);
        if (ongoing) {
            return start;
        }

        final LocalDate lastDay = span.endExclusive().minusDays(1L);
        return lastDay.equals(span.start()) ? start : (start + RANGE_SEPARATOR + lastDay.format(DATE_FMT));
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
    public static String lastLabel(final SubjectStats stats) {
        return stats.lastPerformed() == null ? "Never" : stats.lastPerformed().format(DATE_FMT);
    }

    /**
     * The first-performed date formatted for display at full width ({@code "15 June 2026"}), or "Never" if the action was never logged.
     *
     * @param stats the statistics to inspect
     * @return the formatted first-performed date, or "Never"
     */
    @TemplateExtension
    public static String firstLabel(final SubjectStats stats) {
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
    public static String latestLabel(final SubjectStats stats) {
        final LocalDate lastPerformed = stats.lastPerformed();
        if (lastPerformed == null) {
            return "Never";
        }
        return lastPerformed.getYear() == stats.today().getYear()
                ? lastPerformed.format(DATE_FMT_NO_YEAR)
                : lastPerformed.format(DATE_FMT);
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
    public static String weeklyDayAverage(final SubjectStats stats, final int decimalPlaces) {
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
    public static String monthlyDayAverage(final SubjectStats stats, final int decimalPlaces) {
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
    public static String weeklyCountAverage(final SubjectStats stats, final int decimalPlaces) {
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
    public static String monthlyCountAverage(final SubjectStats stats, final int decimalPlaces) {
        return average(stats, stats.totalCount(), ChronoUnit.MONTHS, decimalPlaces);
    }

    private static String average(final SubjectStats stats, final long total, final ChronoUnit unit, final int decimalPlaces) {
        final LocalDate firstPerformed = stats.firstPerformed();
        if (firstPerformed == null) {
            return formatDecimal(0.0, decimalPlaces);
        }
        final long periods = Math.max(1L, unit.between(firstPerformed, stats.today()));
        return formatDecimal((double) total / periods, decimalPlaces);
    }

    private static String formatDecimal(final double value, final int decimalPlaces) {
        if (value == ZERO) {
            return "0";
        }

        final String format = "%." + decimalPlaces + "f";
        return String.format(Locale.ENGLISH, format, value);
    }

    // ── Streak/gap durations ──────────────────────────────────────────────
    // Every span is worded in exactly one place - durationTile above, through the shared Durations helper - so a
    // long run always condenses the same way ("412 days" -> "1 year, 1 month, 17 days"), a one-unit run never
    // reads "1 months", and a short run is never left as a bare number with its unit demoted to the caption.

    private static DaySpan currentGapSpan(final SubjectStats stats) {
        final LocalDate lastPerformed = stats.lastPerformed();
        return lastPerformed == null
                ? new DaySpan(stats.today(), stats.today())
                : new DaySpan(lastPerformed.plusDays(1L), stats.today().plusDays(1L));
    }

    /**
     * The number of days since the action was last performed ({@code 0} when performed today, or never at all).
     *
     * @param stats the statistics to inspect
     * @return the current gap in days
     */
    @TemplateExtension
    public static int currentGap(final SubjectStats stats) {
        return Durations.days(currentGapSpan(stats));
    }

    // ── Comparative helpers ───────────────────────────────────────────────

    /**
     * This month's count relative to last month's, as a signed trend label.
     *
     * @param stats the statistics to inspect
     * @return the month trend label
     */
    @TemplateExtension
    public static String monthTrend(final SubjectStats stats) {
        return trend(stats.thisMonthCount(), stats.lastMonthCount());
    }

    /**
     * The CSS colour class matching {@link #monthTrend(SubjectStats)} (up/down/flat).
     *
     * @param stats the statistics to inspect
     * @return the month trend colour class
     */
    @TemplateExtension
    public static String monthTrendClass(final SubjectStats stats) {
        return trendClass(stats.thisMonthCount(), stats.lastMonthCount());
    }

    /**
     * A "{@code X this month}" context string (just the current month, no last-month comparison).
     *
     * @param stats the statistics to inspect
     * @return the this-month context string
     */
    @TemplateExtension
    public static String thisMonthContext(final SubjectStats stats) {
        return stats.thisMonthCount() + " this month";
    }

    /**
     * This year's count relative to last year's, as a signed trend label.
     *
     * @param stats the statistics to inspect
     * @return the year trend label
     */
    @TemplateExtension
    public static String yearTrend(final SubjectStats stats) {
        return trend(stats.thisYearCount(), stats.lastYearCount());
    }

    /**
     * The CSS colour class matching {@link #yearTrend(SubjectStats)} (up/down/flat).
     *
     * @param stats the statistics to inspect
     * @return the year trend colour class
     */
    @TemplateExtension
    public static String yearTrendClass(final SubjectStats stats) {
        return trendClass(stats.thisYearCount(), stats.lastYearCount());
    }

    // ── Private ───────────────────────────────────────────────────────────

    private static String trend(final long current, final long previous) {
        if (current == 0L && previous == 0L) {
            return "—";
        }
        if (previous == 0L) {
            return "+" + current;
        }
        final long diff = current - previous;
        if (diff > 0L) {
            return "+" + diff;
        }
        if (diff < 0L) {
            return Long.toString(diff);
        }
        return "=";
    }

    private static String trendClass(final long current, final long previous) {
        final long diff = current - previous;
        if (diff > 0L) {
            return "text-green-600";
        }
        if (diff < 0L) {
            return "text-red-500";
        }
        return "text-gray-400";
    }
}
