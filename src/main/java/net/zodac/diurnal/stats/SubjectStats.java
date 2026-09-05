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
import java.time.YearMonth;
import net.zodac.diurnal.time.DaySpan;
import net.zodac.diurnal.time.Durations;
import org.jspecify.annotations.Nullable;

/**
 * Computed statistics for a single {@link StatSubject} — an action, or the user's day notes — covering totals, streaks, comparative trends and high
 * scores. Every figure is derived from the same shape of input (a set of dated entries with a per-day count), so the subject only says what the
 * figures are ABOUT; it never changes how one is calculated.
 *
 * <p>
 * Intentionally a pure data carrier with no behaviour: all derived labels, trends and predicates live in {@link SubjectStatsExtensions} (as Qute
 * template extensions) so PITest can mutation-test that branching logic. PITest hot-swaps each mutant into the running JVM via
 * {@code Instrumentation.redefineClasses}, which the JVM refuses for a class carrying a {@code Record} attribute — so mutating logic held on this
 * record failed with "class redefinition failed: attempted to change the Record attribute", surfacing as the "Minion exited abnormally due to
 * RUN_ERROR" lint warnings. Keeping the record free of mutable methods means PITest generates no mutants for it, while the extracted logic mutates
 * cleanly.
 *
 * <p>
 * The streak and gap figures are {@link DaySpan}s rather than plain day counts: their length is {@link Durations#days(DaySpan)}, but they are also
 * rendered as calendar durations ("1 year, 1 month, 17 days"), and that breakdown depends on the actual dates the run covered - the same 31 days is
 * "1 month" in one place in the calendar and "1 month, 3 days" in another. Carrying the dates keeps every duration exact and stable over time; a bare
 * count could only be split against some arbitrary anchor, which would make a historical streak's label drift as "today" moved.
 */
public record SubjectStats(
    StatSubject subject,
    int       totalDays,
    // The days the subject was recorded MORE THAN ONCE, and the most recent of them. A notes subject can have neither (one note per day is the
    // whole of a day's history), which is not a special case to suppress - it is the honest figure for a subject no day can hold twice.
    int       daysWithMultiples,
    long      totalCount,
    @Nullable LocalDate firstPerformed,
    @Nullable LocalDate lastPerformed,
    @Nullable LocalDate lastDayWithMultiples,
    DaySpan   currentStreak,
    DaySpan   longestStreak,
    DaySpan   longestGap,
    // Comparative
    // These are longs (where totalDays above is an int) by provenance rather than by scale: each is a MonthlyActionTotal.total(), which JPQL's
    // SUM/COUNT binds as a Long, whereas totalDays is a list size. An int would hold every reachable value, but narrowing means either casting
    // inside the query or Math.toIntExact at the projection, and neither buys anything measurable.
    long      thisMonthCount,
    long      lastMonthCount,
    long      thisYearCount,
    long      lastYearCount,
    // High scores
    // The single busiest day and its count. The date is the EARLIEST day holding that count, so a record reports when it was first SET rather
    // than when it was last equalled; a notes subject consequently reports its first-ever note day at a count of one, which is the honest figure
    // for a subject no day can hold twice.
    @Nullable LocalDate bestDay,
    long      bestDayCount,
    // bestMonth is the raw month, not a pre-formatted label: "June 2026" is a WORD (the month name), and a plain
    // Java call can never be locale-aware (see AppMessages' own class Javadoc) - each surface formats it itself
    // (the API in English, the web template via SubjectStatsExtensions, per the viewing user's language).
    @Nullable YearMonth bestMonth,
    long      bestMonthCount,
    String    bestYearLabel,
    long      bestYearCount,
    // The "now" in the user's configured timezone — never call LocalDate.now() directly
    LocalDate today
) {
}
