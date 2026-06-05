package dev.lifetracker.stats;

import dev.lifetracker.action.Action;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

public record ActionStats(
        Action    action,
        int       totalDays,
        long      totalCount,
        LocalDate firstPerformed,
        LocalDate lastPerformed,
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
    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("MMMM yyyy",   Locale.ENGLISH);

    // ── Existing helpers ──────────────────────────────────────────────────

    public boolean hasData() { return totalDays > 0; }

    public String lastLabel() {
        return lastPerformed == null ? "Never" : lastPerformed.format(DATE_FMT);
    }

    public String sinceLabel() {
        if (lastPerformed == null) return "—";
        long days = ChronoUnit.DAYS.between(lastPerformed, today);
        if (days == 0) return "Today";
        if (days == 1) return "Yesterday";
        return days + " days ago";
    }

    public String weeklyAverage() {
        if (firstPerformed == null) return "0.0";
        long weeks = Math.max(1L, ChronoUnit.WEEKS.between(firstPerformed, today));
        return String.format(Locale.ENGLISH, "%.1f", (double) totalDays / weeks);
    }

    // ── Comparative helpers ───────────────────────────────────────────────

    public String monthTrend() {
        return trend(thisMonthCount, lastMonthCount);
    }

    public String monthTrendClass() {
        return trendClass(thisMonthCount, lastMonthCount);
    }

    public String monthContext() {
        return thisMonthCount + " this month · " + lastMonthCount + " last month";
    }

    public String yearTrend() {
        return trend(thisYearCount, lastYearCount);
    }

    public String yearTrendClass() {
        return trendClass(thisYearCount, lastYearCount);
    }

    public String yearContext() {
        return thisYearCount + " this year · " + lastYearCount + " last year";
    }

    // ── Private ───────────────────────────────────────────────────────────

    private static String trend(long current, long previous) {
        if (current == 0 && previous == 0) return "—";
        if (previous == 0) return "+" + current;
        long diff = current - previous;
        if (diff == 0) return "=";
        return (diff > 0 ? "+" : "") + diff;
    }

    private static String trendClass(long current, long previous) {
        long diff = current - previous;
        if (diff > 0) return "text-green-600";
        if (diff < 0) return "text-red-500";
        return "text-gray-400";
    }
}
