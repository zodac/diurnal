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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;

/**
 * Computes per-action statistics (counts, streaks, trends) from a user's logged entries.
 */
@ApplicationScoped
public class StatsService {

    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    @Inject
    AppClock clock;

    /**
     * Returns stats for every active action of the user that has at least one logged entry.
     */
    @Transactional
    public List<ActionStats> forAllActiveActions(final UUID userId) {
        return computeAll(userId).stream()
                .filter(ActionStatsExtensions::hasData)
                .toList();
    }

    /**
     * Returns stats for the actions the user has performed in the current month, newest first, up to
     * {@code limit}. Actions not logged this month are excluded entirely.
     */
    @Transactional
    public List<ActionStats> forMostRecent(final UUID userId, final int limit) {
        return computeAll(userId).stream()
                .filter(ActionStatsExtensions::performedThisMonth)
                .sorted(Comparator.comparing(ActionStats::lastPerformed).reversed())
                .limit(limit)
                .toList();
    }

    // ── Shared computation ────────────────────────────────────────────────

    private List<ActionStats> computeAll(final UUID userId) {
        final Map<UUID, List<ActionLog>> byAction = ActionLog.findAllByUser(userId)
                .stream().collect(Collectors.groupingBy(l -> l.actionId));
        // Streak boundaries are evaluated in the user's own timezone (else the server default).
        final User user = User.findById(userId);
        final LocalDate today = clock.today(clock.zoneFor(user == null ? null : user.timezone));
        return Action.findActiveByUser(userId).stream()
                .map(a -> compute(a, byAction.getOrDefault(a.id, List.of()), today))
                .toList();
    }

    private static ActionStats compute(final Action action, final List<ActionLog> logs, final LocalDate today) {
        if (logs.isEmpty()) {
            return new ActionStats(action, 0, 0L, null, null, 0, 0, 0,
                    0L, 0L, 0L, 0L, "—", 0L, "—", 0L, today);
        }

        final List<LocalDate> dates = logs.stream()
                .map(l -> l.logDate).distinct().sorted().toList();

        final long totalCount = logs.stream().mapToLong(l -> l.count).sum();

        final YearMonth thisMonth = YearMonth.from(today);
        final YearMonth prevMonth = thisMonth.minusMonths(1);
        final int thisYear = today.getYear();

        final Map<YearMonth, Long> byMonth = logs.stream().collect(
                Collectors.groupingBy(l -> YearMonth.from(l.logDate),
                        Collectors.summingLong(l -> l.count)));
        final Map<Integer, Long> byYear = logs.stream().collect(
                Collectors.groupingBy(l -> l.logDate.getYear(),
                        Collectors.summingLong(l -> l.count)));

        final Map.Entry<YearMonth, Long> bestMonth = byMonth.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
        final Map.Entry<Integer, Long> bestYear = byYear.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);

        return new ActionStats(
                action,
                dates.size(),
                totalCount,
                dates.getFirst(),
                dates.getLast(),
                currentStreak(dates, today),
                longestStreak(dates),
                longestGap(dates, today),
                byMonth.getOrDefault(thisMonth, 0L),
                byMonth.getOrDefault(prevMonth, 0L),
                byYear.getOrDefault(thisYear, 0L),
                byYear.getOrDefault(thisYear - 1, 0L),
                bestMonth != null ? bestMonth.getKey().format(MONTH_FMT) : "—",
                bestMonth != null ? bestMonth.getValue() : 0L,
                bestYear  != null ? String.valueOf(bestYear.getKey()) : "—",
                bestYear  != null ? bestYear.getValue() : 0L,
                today);
    }

    /**
     * The number of consecutive days up to (and including) today on which the action was performed.
     */
    static int currentStreak(final List<LocalDate> sortedDates, final LocalDate today) {
        final Set<LocalDate> set = new HashSet<>(sortedDates);
        LocalDate cursor = set.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (set.contains(cursor)) {
            streak++;
            cursor = cursor.minusDays(1);
        }
        return streak;
    }

    /**
     * The longest span of consecutive days on which the action was <em>not</em> performed, looking
     * both at gaps between any two logged dates and at the open gap from the last log to today.
     */
    static int longestGap(final List<LocalDate> sortedDates, final LocalDate today) {
        if (sortedDates.isEmpty()) {
            return 0;
        }
        int longest = 0;
        for (int i = 1; i < sortedDates.size(); i++) {
            final int gap = (int) (ChronoUnit.DAYS.between(sortedDates.get(i - 1), sortedDates.get(i)) - 1);
            if (gap > longest) {
                longest = gap;
            }
        }
        final int openGap = (int) ChronoUnit.DAYS.between(sortedDates.getLast(), today);
        return Math.max(longest, openGap);
    }

    /**
     * The longest run of consecutive performed days anywhere in the action's history.
     */
    static int longestStreak(final List<LocalDate> sortedDates) {
        if (sortedDates.isEmpty()) {
            return 0;
        }
        int longest = 1;
        int run = 1;
        for (int i = 1; i < sortedDates.size(); i++) {
            if (sortedDates.get(i).equals(sortedDates.get(i - 1).plusDays(1))) {
                run++;
                longest = Math.max(longest, run);
            } else {
                run = 1;
            }
        }
        return longest;
    }
}
