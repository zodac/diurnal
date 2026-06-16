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
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    // ── Existing helpers ──────────────────────────────────────────────────

    /** Whether this action has any logged data (used to filter empty actions out of stats). */
    public boolean hasData() {
        return totalDays > 0;
    }

    /** The last-performed date formatted for display, or "Never" if the action was never logged. */
    @SuppressWarnings("unused") // invoked from Qute templates (stats-cards.html, dashboard.html)
    public String lastLabel() {
        return lastPerformed == null ? "Never" : lastPerformed.format(DATE_FMT);
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
