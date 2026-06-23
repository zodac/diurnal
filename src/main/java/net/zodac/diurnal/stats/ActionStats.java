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
import net.zodac.diurnal.action.Action;
import org.jspecify.annotations.Nullable;

/**
 * Computed statistics for a single action — totals, streaks, comparative trends and high scores.
 *
 * <p>Intentionally a pure data carrier with no behaviour: all derived labels, trends and predicates
 * live in {@link ActionStatsExtensions} (as Qute template extensions) so PITest can mutation-test
 * that branching logic. PITest hot-swaps each mutant into the running JVM via
 * {@code Instrumentation.redefineClasses}, which the JVM refuses for a class carrying a
 * {@code Record} attribute — so mutating logic held on this record failed with
 * "class redefinition failed: attempted to change the Record attribute", surfacing as the
 * "Minion exited abnormally due to RUN_ERROR" lint warnings. Keeping the record free of mutable
 * methods means PITest generates no mutants for it, while the extracted logic mutates cleanly.
 */
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
}
