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

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;
import net.zodac.diurnal.stats.cache.SubjectStatsCache;
import net.zodac.diurnal.time.DaySpan;

/**
 * Converts between a {@link SubjectStats} and the {@link SubjectStatsCache} row that stores it.
 *
 * <p>
 * This lives here rather than on the entity so that {@code stats.cache} stays a sink - a package the log- and note-writing packages can import to
 * invalidate the cache without creating a cycle back into {@code stats}, which itself reads {@code log} and {@code note}. The entity is therefore
 * pure columns, and everything that knows what those columns MEAN is in this class.
 *
 * <p>
 * The subject is not stored and is not round-tripped: {@link #toStats(SubjectStatsCache, StatSubject)} takes the subject its caller resolved live, so
 * an action's name and colour always render as they are now rather than as they were when the figures were computed.
 */
final class SubjectStatsCaching {

    /**
     * The label {@link SubjectStats#bestYearLabel()} carries when the subject has no history at all, and so no best year. Matching this on the way in
     * is what lets {@code best_year} be stored as the year itself rather than as a rendered string.
     */
    static final String NO_BEST_YEAR = "—";

    private SubjectStatsCaching() {

    }

    /**
     * Builds the cache row holding one subject's freshly computed figures.
     *
     * @param userId the owning user
     * @param stats the computed statistics
     * @return the row to persist
     */
    static SubjectStatsCache from(final UUID userId, final SubjectStats stats) {
        final SubjectStatsCache row = new SubjectStatsCache();
        row.userId = userId;
        row.subjectId = stats.subject().id();
        row.computedForDate = stats.today();
        row.totalDays = stats.totalDays();
        row.daysWithMultiples = stats.daysWithMultiples();
        row.totalCount = stats.totalCount();
        row.firstPerformed = stats.firstPerformed();
        row.lastPerformed = stats.lastPerformed();
        row.lastDayWithMultiples = stats.lastDayWithMultiples();
        row.currentStreakStart = stats.currentStreak().start();
        row.currentStreakEnd = stats.currentStreak().endExclusive();
        row.longestStreakStart = stats.longestStreak().start();
        row.longestStreakEnd = stats.longestStreak().endExclusive();
        row.longestGapStart = stats.longestGap().start();
        row.longestGapEnd = stats.longestGap().endExclusive();
        row.thisMonthCount = stats.thisMonthCount();
        row.lastMonthCount = stats.lastMonthCount();
        row.thisYearCount = stats.thisYearCount();
        row.lastYearCount = stats.lastYearCount();
        row.bestDay = stats.bestDay();
        row.bestDayCount = stats.bestDayCount();
        // Both stay at their column default of null when the subject has no history; a subject with no best month has no best year either.
        final YearMonth bestMonth = stats.bestMonth();
        if (bestMonth != null) {
            row.bestMonth = bestMonth.atDay(1);
        }
        if (!NO_BEST_YEAR.equals(stats.bestYearLabel())) {
            row.bestYear = Integer.valueOf(stats.bestYearLabel());
        }
        row.bestMonthCount = stats.bestMonthCount();
        row.bestYearCount = stats.bestYearCount();
        row.updatedAt = Instant.now();
        return row;
    }

    /**
     * Rebuilds a cached row into a {@link SubjectStats} for the given subject. The subject carries the name and colour, which are never stored;
     * everything else comes off the row.
     *
     * @param row the cached row
     * @param subject the subject the figures are about, resolved live by the caller
     * @return the reconstructed statistics
     */
    static SubjectStats toStats(final SubjectStatsCache row, final StatSubject subject) {
        return new SubjectStats(
            subject,
            row.totalDays,
            row.daysWithMultiples,
            row.totalCount,
            row.firstPerformed,
            row.lastPerformed,
            row.lastDayWithMultiples,
            new DaySpan(row.currentStreakStart, row.currentStreakEnd),
            new DaySpan(row.longestStreakStart, row.longestStreakEnd),
            new DaySpan(row.longestGapStart, row.longestGapEnd),
            row.thisMonthCount,
            row.lastMonthCount,
            row.thisYearCount,
            row.lastYearCount,
            row.bestDay,
            row.bestDayCount,
            row.bestMonth == null ? null : YearMonth.from(row.bestMonth),
            row.bestMonthCount,
            row.bestYear == null ? NO_BEST_YEAR : String.valueOf(row.bestYear),
            row.bestYearCount,
            row.computedForDate);
    }
}
