package dev.lifetracker.stats;

import dev.lifetracker.action.Action;
import dev.lifetracker.log.ActionLog;
import dev.lifetracker.time.AppClock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ApplicationScoped
public class StatsService {

    private static final DateTimeFormatter MONTH_FMT =
            DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    @Inject
    AppClock clock;

    @Transactional
    public List<ActionStats> forAllActiveActions(UUID userId) {
        return computeAll(userId).stream()
                .filter(ActionStats::hasData)
                .toList();
    }

    @Transactional
    public List<ActionStats> forMostRecent(UUID userId, int limit) {
        return computeAll(userId).stream()
                .filter(ActionStats::hasData)
                .sorted(Comparator.comparing(ActionStats::lastPerformed).reversed())
                .limit(limit)
                .toList();
    }

    // ── Shared computation ────────────────────────────────────────────────

    private List<ActionStats> computeAll(UUID userId) {
        Map<UUID, List<ActionLog>> byAction = ActionLog.findAllByUser(userId)
                .stream().collect(Collectors.groupingBy(l -> l.actionId));
        LocalDate today = clock.today();
        return Action.findActiveByUser(userId).stream()
                .map(a -> compute(a, byAction.getOrDefault(a.id, List.of()), today))
                .toList();
    }

    private static ActionStats compute(Action action, List<ActionLog> logs, LocalDate today) {
        if (logs.isEmpty()) {
            return new ActionStats(action, 0, 0L, null, null, 0, 0,
                    0L, 0L, 0L, 0L, "—", 0L, "—", 0L, today);
        }

        List<LocalDate> dates = logs.stream()
                .map(l -> l.logDate).distinct().sorted().toList();

        long totalCount = logs.stream().mapToLong(l -> l.count).sum();

        YearMonth thisMonth = YearMonth.from(today);
        YearMonth prevMonth = thisMonth.minusMonths(1);
        int thisYear = today.getYear();

        Map<YearMonth, Long> byMonth = logs.stream().collect(
                Collectors.groupingBy(l -> YearMonth.from(l.logDate),
                        Collectors.summingLong(l -> l.count)));
        Map<Integer, Long> byYear = logs.stream().collect(
                Collectors.groupingBy(l -> l.logDate.getYear(),
                        Collectors.summingLong(l -> l.count)));

        Map.Entry<YearMonth, Long> bestMonth = byMonth.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);
        Map.Entry<Integer, Long> bestYear = byYear.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElse(null);

        return new ActionStats(
                action,
                dates.size(),
                totalCount,
                dates.getFirst(),
                dates.getLast(),
                currentStreak(dates, today),
                longestStreak(dates),
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

    static int currentStreak(List<LocalDate> sortedDates, LocalDate today) {
        Set<LocalDate> set = new HashSet<>(sortedDates);
        LocalDate cursor = set.contains(today) ? today : today.minusDays(1);
        int streak = 0;
        while (set.contains(cursor)) { streak++; cursor = cursor.minusDays(1); }
        return streak;
    }

    static int longestStreak(List<LocalDate> sortedDates) {
        if (sortedDates.isEmpty()) return 0;
        int longest = 1, run = 1;
        for (int i = 1; i < sortedDates.size(); i++) {
            if (sortedDates.get(i).equals(sortedDates.get(i - 1).plusDays(1))) {
                longest = Math.max(longest, ++run);
            } else {
                run = 1;
            }
        }
        return longest;
    }
}
