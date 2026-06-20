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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import net.zodac.diurnal.action.Action;
import org.jspecify.annotations.Nullable;

/** Computed statistics for a single action — totals, streaks, comparative trends and high scores. */
public record ActionStats(
        Action    action,
        int       totalDays,
        long      totalCount,
        @Nullable LocalDate firstPerformed,
        @Nullable LocalDate lastPerformed,
        int       currentStreak,
        int       longestStreak,
        // Comparative
        long      thisMonthCount,
        long      lastMonthCount,
        long      thisYearCount,
        long      lastYearCount,
        // High scores
        String    bestMonthLabel,
        long      bestMonthCount,
        String    bestYearLabel,
        long      bestYearCount,
        // The "now" in the user's configured timezone — never call LocalDate.now() directly
        LocalDate today
) {
    private static final DateTimeFormatter DATE_FMT  =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_FMT_NO_YEAR =
            DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    // ── Existing helpers ──────────────────────────────────────────────────

    /** Whether this action has any logged data (used to filter empty actions out of stats). */
    public boolean hasData() {
        return totalDays > 0;
    }

    /** Whether this action was performed at least once in the current month. */
    public boolean performedThisMonth() {
        return thisMonthCount > 0;
    }

    /** The last-performed date formatted for display, or "Never" if the action was never logged. */
    @SuppressWarnings("unused") // invoked from Qute templates (stats-cards.html)
    public String lastLabel() {
        return lastPerformed == null ? "Never" : lastPerformed.format(DATE_FMT);
    }

    /**
     * The last-performed date for the dashboard "Latest" label: "Never" if never logged, "d MMM" (no
     * year) when it falls in the current year, and the full "d MMM yyyy" only for an earlier year.
     */
    public String latestLabel() {
        if (lastPerformed == null) {
            return "Never";
        }
        return lastPerformed.getYear() == today.getYear()
                ? lastPerformed.format(DATE_FMT_NO_YEAR)
                : lastPerformed.format(DATE_FMT);
    }

    /** A relative label for the last-performed date: "Today", "Yesterday" or "N days ago". */
    public String sinceLabel() {
        if (lastPerformed == null) {
            return "—";
        }
        final long days = ChronoUnit.DAYS.between(lastPerformed, today);
        if (days == 0) {
            return "Today";
        }
        if (days == 1) {
            return "Yesterday";
        }
        return days + " days ago";
    }

    /** The average number of active days per week since the action was first performed. */
    public String weeklyAverage() {
        if (firstPerformed == null) {
            return "0.0";
        }
        final long weeks = Math.max(1L, ChronoUnit.WEEKS.between(firstPerformed, today));
        return String.format(Locale.ENGLISH, "%.1f", (double) totalDays / weeks);
    }

    // ── Singular-aware day/streak labels ──────────────────────────────────

    /** The current streak as a singular-aware label, e.g. {@code "1 day"} or {@code "5 days"}. */
    public String currentStreakLabel() {
        return currentStreak + " " + currentStreakUnit();
    }

    /** The longest streak as a singular-aware label, e.g. {@code "1 day"} or {@code "5 days"}. */
    public String longestStreakLabel() {
        return longestStreak + " " + longestStreakUnit();
    }

    /** The unit word ({@code "day"}/{@code "days"}) matching the current-streak count. */
    public String currentStreakUnit() {
        return plural(currentStreak, "day");
    }

    /** The unit word ({@code "day"}/{@code "days"}) matching the longest-streak count. */
    public String longestStreakUnit() {
        return plural(longestStreak, "day");
    }

    /** The unit phrase ({@code "distinct day"}/{@code "distinct days"}) matching the total-days count. */
    public String totalDaysUnit() {
        return plural(totalDays, "distinct day");
    }

    // ── Comparative helpers ───────────────────────────────────────────────

    /** This month's count relative to last month's, as a signed trend label. */
    public String monthTrend() {
        return trend(thisMonthCount, lastMonthCount);
    }

    /** The CSS colour class matching {@link #monthTrend()} (up/down/flat). */
    public String monthTrendClass() {
        return trendClass(thisMonthCount, lastMonthCount);
    }

    /** A "{@code X this month · Y last month}" context string. */
    public String monthContext() {
        return thisMonthCount + " this month · " + lastMonthCount + " last month";
    }

    /** A "{@code X this month}" context string (just the current month, no last-month comparison). */
    public String thisMonthContext() {
        return thisMonthCount + " this month";
    }

    /** This year's count relative to last year's, as a signed trend label. */
    public String yearTrend() {
        return trend(thisYearCount, lastYearCount);
    }

    /** The CSS colour class matching {@link #yearTrend()} (up/down/flat). */
    public String yearTrendClass() {
        return trendClass(thisYearCount, lastYearCount);
    }

    /** A "{@code X this year · Y last year}" context string. */
    public String yearContext() {
        return thisYearCount + " this year · " + lastYearCount + " last year";
    }

    // ── Private ───────────────────────────────────────────────────────────

    /**
     * Pluralises a unit word for a count: the bare {@code unit} when {@code count == 1}, otherwise
     * {@code unit + "s"}. The single source of the app's singular/plural rule for UI-facing text.
     *
     * @param count the quantity the unit describes
     * @param unit  the singular unit word (e.g. {@code "day"})
     * @return {@code unit}, pluralised to match {@code count}
     */
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
        if (diff == 0) {
            return "=";
        }
        return (diff > 0 ? "+" : "") + diff;
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
