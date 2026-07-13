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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
     * Returns stats for every active action of the user that has at least one logged entry, ordered by action name.
     *
     * <p>
     * The per-action totals, comparative counts and best-month/best-year figures are aggregated in the database (a monthly {@code GROUP BY}); only
     * the distinct performed dates are read back, and solely to compute the streak/gap figures — so a long history no longer hydrates every log row.
     */
    @Transactional
    public List<ActionStats> forAllActiveActions(final UUID userId) {
        final LocalDate today = todayFor(userId);
        final List<Action> actions = Action.findByUser(userId);   // name-ascending
        if (actions.isEmpty()) {
            return List.of();
        }
        final List<UUID> actionIds = actions.stream().map(action -> action.id).toList();
        return assembleAll(userId, actions, actionIds, today).stream()
                .filter(ActionStatsExtensions::hasData)
                .toList();
    }

    /**
     * Returns stats for the actions the user has performed in the current month, newest first, up to {@code limit}. Actions not logged this month are
     * excluded entirely.
     *
     * <p>
     * Unlike {@link #forAllActiveActions(UUID)}, this dashboard path never touches every action: the database picks the {@code limit}
     * most-recently-performed active actions logged this month (ties broken by name, matching the Stats page's ordering), and only those few are
     * aggregated — the only actions the dashboard summary strip can show.
     */
    @Transactional
    public List<ActionStats> forMostRecent(final UUID userId, final int limit) {
        final LocalDate today = todayFor(userId);
        final LocalDate monthStart = today.withDayOfMonth(1);

        final List<UUID> recentActionIds = ActionLog.mostRecentActiveActionIds(userId, monthStart, today, limit);
        if (recentActionIds.isEmpty()) {
            return List.of();
        }

        // findByUserAndIds does not preserve the DB's recency ordering, so restore it by id index.
        final List<Action> actions = Action.findByUserAndIds(userId, recentActionIds).stream()
            .sorted(Comparator.comparingInt((Action action) -> recentActionIds.indexOf(action.id)))
            .toList();
        return assembleAll(userId, actions, recentActionIds, today);
    }

    // ── Shared computation ────────────────────────────────────────────────

    private LocalDate todayFor(final UUID userId) {
        // Streak boundaries are evaluated in the user's own timezone (else the server default).
        final User user = User.findById(userId);
        return clock.today(clock.zoneFor(user == null ? null : user.timezone));
    }

    private static List<ActionStats> assembleAll(final UUID userId, final List<Action> actions,
        final List<UUID> actionIds, final LocalDate today) {
        final Map<UUID, List<MonthlyTotal>> monthly = groupMonthly(ActionLog.monthlyTotalsForActions(userId, actionIds));
        final Map<UUID, List<LocalDate>> dates = groupDates(ActionLog.distinctDatesForActions(userId, actionIds));
        return actions.stream()
                .map(action -> assemble(action, monthly.getOrDefault(action.id, List.of()),
                        dates.getOrDefault(action.id, List.of()), today))
                .toList();
    }

    private static Map<UUID, List<MonthlyTotal>> groupMonthly(final List<Object[]> rows) {
        final Map<UUID, List<MonthlyTotal>> byAction = new HashMap<>();
        for (final Object[] row : rows) {
            final MonthlyTotal total = new MonthlyTotal(
                ((Number) row[1]).intValue(), ((Number) row[2]).intValue(), ((Number) row[3]).longValue());
            byAction.computeIfAbsent((UUID) row[0], _ -> new ArrayList<>()).add(total);
        }
        return byAction;
    }

    private static Map<UUID, List<LocalDate>> groupDates(final List<Object[]> rows) {
        // Rows arrive ordered by (action, date), so each action's list is ascending and distinct.
        final Map<UUID, List<LocalDate>> byAction = new HashMap<>();
        for (final Object[] row : rows) {
            byAction.computeIfAbsent((UUID) row[0], _ -> new ArrayList<>()).add((LocalDate) row[1]);
        }
        return byAction;
    }

    private static ActionStats assemble(final Action action, final List<MonthlyTotal> monthlyTotals,
        final List<LocalDate> sortedDates, final LocalDate today) {
        if (sortedDates.isEmpty()) {
            return new ActionStats(action, 0, 0L, null, null, 0, 0, 0,
                    0L, 0L, 0L, 0L, "—", 0L, "—", 0L, today);
        }

        final YearMonth thisMonth = YearMonth.from(today);
        final YearMonth prevMonth = thisMonth.minusMonths(1);
        final int thisYear = today.getYear();

        final Map<YearMonth, Long> byMonth = new HashMap<>();
        final Map<Integer, Long> byYear = new HashMap<>();
        long totalCount = 0L;
        for (final MonthlyTotal monthlyTotal : monthlyTotals) {
            byMonth.merge(YearMonth.of(monthlyTotal.year(), monthlyTotal.month()), monthlyTotal.total(), Long::sum);
            byYear.merge(monthlyTotal.year(), monthlyTotal.total(), Long::sum);
            totalCount += monthlyTotal.total();
        }

        final Map.Entry<YearMonth, Long> bestMonth = byMonth.entrySet().stream()
            .max(Map.Entry.comparingByValue()).orElse(null);
        final Map.Entry<Integer, Long> bestYear = byYear.entrySet().stream()
            .max(Map.Entry.comparingByValue()).orElse(null);

        return new ActionStats(
                action,
                sortedDates.size(),
                totalCount,
                sortedDates.getFirst(),
                sortedDates.getLast(),
                currentStreak(sortedDates, today),
                longestStreak(sortedDates),
                longestGap(sortedDates, today),
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

    private record MonthlyTotal(int year, int month, long total) {

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
     * The longest span of consecutive days on which the action was <em>not</em> performed, looking both at gaps between any two logged dates and at
     * the open gap from the last log to today.
     */
    static int longestGap(final List<LocalDate> sortedDates, final LocalDate today) {
        if (sortedDates.isEmpty()) {
            return 0;
        }
        int longest = 0;
        for (int i = 1; i < sortedDates.size(); i++) {
            final int gap = (int) (ChronoUnit.DAYS.between(sortedDates.get(i - 1), sortedDates.get(i)) - 1);
            longest = Math.max(longest, gap);
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
