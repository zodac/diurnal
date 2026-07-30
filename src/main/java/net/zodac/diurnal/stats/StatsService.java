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
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
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
import net.zodac.diurnal.log.ActionPerformedDate;
import net.zodac.diurnal.log.MonthlyActionTotal;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.time.DaySpan;
import net.zodac.diurnal.time.Durations;
import net.zodac.diurnal.user.User;

/**
 * Computes per-action statistics (counts, streaks, trends) from a user's logged entries.
 */
@ApplicationScoped
public class StatsService {

    private static final DateTimeFormatter MONTH_FMT =
        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final AppClock clock;

    /**
     * Injects the application clock.
     *
     * @param clock the application clock for date-boundary logic
     */
    @Inject
    public StatsService(final AppClock clock) {
        this.clock = clock;
    }

    /**
     * Returns stats for every active action of the user that has at least one logged entry, ordered by action name.
     *
     * <p>
     * The per-action totals, comparative counts and best-month/best-year figures are aggregated in the database (a monthly {@code GROUP BY}); only
     * the distinct performed dates are read back, and solely to compute the streak/gap figures — so a long history no longer hydrates every log row.
     */
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
        final Map<UUID, List<MonthlyActionTotal>> monthly = groupMonthly(ActionLog.monthlyTotalsForActions(userId, actionIds));
        final Map<UUID, List<LocalDate>> dates = groupDates(ActionLog.distinctDatesForActions(userId, actionIds));
        return actions.stream()
                .map(action -> assemble(action, monthly.getOrDefault(action.id, List.of()),
                        dates.getOrDefault(action.id, List.of()), today))
                .toList();
    }

    private static Map<UUID, List<MonthlyActionTotal>> groupMonthly(final List<MonthlyActionTotal> rows) {
        final Map<UUID, List<MonthlyActionTotal>> byAction = new HashMap<>();
        for (final MonthlyActionTotal row : rows) {
            byAction.computeIfAbsent(row.actionId(), _ -> new ArrayList<>()).add(row);
        }
        return byAction;
    }

    private static Map<UUID, List<LocalDate>> groupDates(final List<ActionPerformedDate> rows) {
        // Rows arrive ordered by (action, date), so each action's list is ascending and distinct.
        final Map<UUID, List<LocalDate>> byAction = new HashMap<>();
        for (final ActionPerformedDate row : rows) {
            byAction.computeIfAbsent(row.actionId(), _ -> new ArrayList<>()).add(row.date());
        }
        return byAction;
    }

    private static ActionStats assemble(final Action action, final List<MonthlyActionTotal> monthlyTotals,
        final List<LocalDate> sortedDates, final LocalDate today) {
        if (sortedDates.isEmpty()) {
            final DaySpan noSpan = new DaySpan(today, today);
            return new ActionStats(action, 0, 0L, null, null, noSpan, noSpan, noSpan,
                    0L, 0L, 0L, 0L, "—", 0L, "—", 0L, today);
        }

        final YearMonth thisMonth = YearMonth.from(today);
        final YearMonth prevMonth = thisMonth.minusMonths(1);
        final int thisYear = today.getYear();

        final Map<YearMonth, Long> byMonth = new HashMap<>();
        final Map<Integer, Long> byYear = new HashMap<>();
        long totalCount = 0L;
        for (final MonthlyActionTotal monthlyTotal : monthlyTotals) {
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
                longestStreak(sortedDates, today),
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

    // ── Streaks and gaps ──────────────────────────────────────────────────
    // Each of these returns the run's actual DATES (a half-open DaySpan), not just its length: the figures are
    // displayed as calendar durations, and "31 days" is one month or one month and three days depending on which
    // months it covered, so the breakdown can only be computed from the real range. An action with no logged
    // dates yields an empty span anchored at today.

    /**
     * The run of consecutive days, up to (and including) today, on which the action was performed. A run still counts as current when today itself
     * has not been logged yet but yesterday was, so a streak is not reported as broken until a whole day has been missed.
     */
    static DaySpan currentStreak(final List<LocalDate> sortedDates, final LocalDate today) {
        final Set<LocalDate> performed = new HashSet<>(sortedDates);
        final LocalDate lastDay = performed.contains(today) ? today : today.minusDays(1);
        if (!performed.contains(lastDay)) {
            return new DaySpan(today, today);
        }

        LocalDate firstDay = lastDay;
        while (performed.contains(firstDay.minusDays(1))) {
            firstDay = firstDay.minusDays(1);
        }
        return new DaySpan(firstDay, lastDay.plusDays(1));
    }

    /**
     * The longest run of consecutive days on which the action was <em>not</em> performed, looking both at the blank days between any two logged dates
     * and at the open run from the last logged date to today. Ties keep the earliest run.
     */
    static DaySpan longestGap(final List<LocalDate> sortedDates, final LocalDate today) {
        if (sortedDates.isEmpty()) {
            return new DaySpan(today, today);
        }

        DaySpan longest = new DaySpan(today, today);
        for (int i = 1; i < sortedDates.size(); i++) {
            // The blank days between two logged dates: the day after the earlier one, up to (excluding) the later.
            final DaySpan gap = new DaySpan(sortedDates.get(i - 1).plusDays(1), sortedDates.get(i));
            longest = longer(longest, gap);
        }
        final DaySpan openGap = new DaySpan(sortedDates.getLast().plusDays(1), today.plusDays(1));
        return longer(longest, openGap);
    }

    /**
     * The longest run of consecutive performed days anywhere in the action's history. Ties keep the earliest run.
     */
    static DaySpan longestStreak(final List<LocalDate> sortedDates, final LocalDate today) {
        if (sortedDates.isEmpty()) {
            return new DaySpan(today, today);
        }

        final LocalDate first = sortedDates.getFirst();
        DaySpan longest = new DaySpan(first, first.plusDays(1));
        LocalDate runStart = first;
        for (int i = 1; i < sortedDates.size(); i++) {
            final LocalDate date = sortedDates.get(i);
            if (!date.equals(sortedDates.get(i - 1).plusDays(1))) {
                runStart = date;
            }
            longest = longer(longest, new DaySpan(runStart, date.plusDays(1)));
        }
        return longest;
    }

    private static DaySpan longer(final DaySpan current, final DaySpan candidate) {
        return Durations.days(candidate) > Durations.days(current) ? candidate : current;
    }
}
