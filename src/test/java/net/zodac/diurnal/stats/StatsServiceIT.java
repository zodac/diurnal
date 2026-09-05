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

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.time.Durations;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link StatsService#forDate(UUID, LocalDate, int)} and {@link StatsService#forMonth(UUID, YearMonth, int)} — the dashboard
 * summary path, which follows the calendar's selected day.
 *
 * <p>
 * These assert observable behaviour only (which actions are returned, in what order, and that the stats reflect the actions' <em>full</em> history),
 * so they hold regardless of whether the selection is done in Java or pushed into SQL.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // userId populated in createDbState(), called from the base @BeforeEach
class StatsServiceIT extends IntegrationTestBase {

    private static final LocalDate TODAY = FIXED_TODAY;              // 2026-06-15
    private static final LocalDate MONTH_START = TODAY.withDayOfMonth(1);
    private static final LocalDate LAST_MONTH = TODAY.minusMonths(1);

    @Inject
    private StatsService statsService;

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser("stats-svc@lt.test", "Stats Service User").id;
    }

    private List<String> topNames(final int limit) {
        return statsService.forDate(userId, TODAY, limit).stream()
                .map(subjectStats -> subjectStats.subject().name())
                .toList();
    }

    @Test
    void forDate_excludesActionsNotLoggedOnThatDay() {
        runInTx(() -> {
            final Action onTheDay = newAction(userId, "OnTheDay");
            newLog(userId, onTheDay.id, TODAY, 1);
            final Action anotherDay = newAction(userId, "AnotherDay");
            newLog(userId, anotherDay.id, TODAY.minusDays(1L), 1);
        });

        assertThat(topNames(10))
                .as("only actions logged on the selected day should appear")
                .containsExactly("OnTheDay");
    }

    @Test
    void forDate_ordersByThatDaysCountDescending() {
        // Names are deliberately in the opposite order to the counts, so a name-ascending result would
        // differ from the expected count ordering.
        runInTx(() -> {
            final Action alpha = newAction(userId, "Alpha");
            newLog(userId, alpha.id, TODAY, 1);
            final Action bravo = newAction(userId, "Bravo");
            newLog(userId, bravo.id, TODAY, 9);
            final Action charlie = newAction(userId, "Charlie");
            newLog(userId, charlie.id, TODAY, 5);
        });

        assertThat(topNames(10))
                .as("actions should be ordered by the selected day's count, highest first")
                .containsExactly("Bravo", "Charlie", "Alpha");
    }

    @Test
    void forDate_tiesBrokenByNameAscending() {
        runInTx(() -> {
            final Action zebra = newAction(userId, "Zebra");
            newLog(userId, zebra.id, TODAY, 3);
            final Action apple = newAction(userId, "Apple");
            newLog(userId, apple.id, TODAY, 3);
        });

        assertThat(topNames(10))
                .as("actions logged the same number of times should be ordered by name")
                .containsExactly("Apple", "Zebra");
    }

    @Test
    void forDate_respectsLimit() {
        runInTx(() -> {
            for (int count = 1; count <= 4; count++) {
                final Action action = newAction(userId, "Action" + count);
                newLog(userId, action.id, TODAY, count);
            }
        });

        // Counts 4 and 3 are the two highest — Action4 then Action3.
        assertThat(topNames(2))
                .as("only the day's top `limit` actions should be returned")
                .containsExactly("Action4", "Action3");
    }

    @Test
    void forDate_computesStatsOverFullHistoryNotJustThatDay() {
        runInTx(() -> {
            final Action action = newAction(userId, "LongHistory");
            newLog(userId, action.id, TODAY, 2);                    // the selected day
            newLog(userId, action.id, TODAY.minusMonths(2), 3);     // April — outside the day
        });

        final List<SubjectStats> stats = statsService.forDate(userId, TODAY, 10);
        assertThat(stats)
                .as("the action logged on the selected day should be present")
                .hasSize(1);
        assertThat(stats.getFirst().totalCount())
                .as("total count must reflect the action's full history, not just the selected day")
                .isEqualTo(5L);
        assertThat(stats.getFirst().totalDays())
                .as("total distinct days must reflect the action's full history")
                .isEqualTo(2);
    }

    @Test
    void forDate_emptyWhenNothingLoggedOnThatDay() {
        runInTx(() -> {
            final Action action = newAction(userId, "Stale");
            newLog(userId, action.id, LAST_MONTH, 1);
        });

        assertThat(statsService.forDate(userId, TODAY, 10))
                .as("no actions logged on the selected day should yield an empty result")
                .isEmpty();
    }

    @Test
    void forMonth_summarisesEachLoggedDayAndOmitsBlankOnes() {
        final LocalDate second = MONTH_START.plusDays(1);
        runInTx(() -> {
            final Action reading = newAction(userId, "Reading");
            newLog(userId, reading.id, MONTH_START, 2);
            newLog(userId, reading.id, second, 1);
            final Action running = newAction(userId, "Running");
            newLog(userId, running.id, second, 7);
        });

        final Map<LocalDate, List<SubjectStats>> byDate = statsService.forMonth(userId, YearMonth.from(TODAY), 3);
        assertThat(byDate.keySet())
                .as("only days with logged actions should be present")
                .containsExactlyInAnyOrder(MONTH_START, second);
        final List<SubjectStats> first = byDate.getOrDefault(MONTH_START, List.of());
        final List<SubjectStats> secondDay = byDate.getOrDefault(second, List.of());
        assertThat(first.stream().map(subjectStats -> subjectStats.subject().name()).toList())
                .as("the 1st only has Reading logged")
                .containsExactly("Reading");
        assertThat(secondDay.stream().map(subjectStats -> subjectStats.subject().name()).toList())
                .as("the 2nd orders by that day's count, so Running (7) precedes Reading (1)")
                .containsExactly("Running", "Reading");
        assertThat(first.getFirst().totalCount())
                .as("each day's figures still span the action's full history")
                .isEqualTo(3L);
    }

    @Test
    void forMonth_emptyWhenNothingLoggedInTheMonth() {
        runInTx(() -> {
            final Action action = newAction(userId, "Stale");
            newLog(userId, action.id, LAST_MONTH, 1);
        });

        assertThat(statsService.forMonth(userId, YearMonth.from(TODAY), 3))
                .as("a month with no logged actions should yield an empty map")
                .isEmpty();
    }

    @Test
    void forAllSubjects_excludesUnloggedActionsAndOrdersByName() {
        runInTx(() -> {
            newAction(userId, "Zeta");                                   // no logs — excluded
            final Action logged = newAction(userId, "Beta");
            newLog(userId, logged.id, TODAY, 1);
            newAction(userId, "Alpha");                                  // no logs — excluded
        });

        assertThat(statsService.forAllSubjects(userId).stream().map(subjectStats -> subjectStats.subject().name()).toList())
                .as("only actions with logged data should appear")
                .containsExactly("Beta");
    }

    @Test
    void forAllSubjects_aggregatesMonthlyAndYearlyTotalsFromSql() {
        // A history spanning several months and two years, exercising the DB-side monthly aggregation.
        runInTx(() -> {
            final Action action = newAction(userId, "Spanning");
            newLog(userId, action.id, TODAY, 2);                         // Jun 2026
            newLog(userId, action.id, MONTH_START.plusDays(9), 3);       // Jun 2026 (10th)
            newLog(userId, action.id, LAST_MONTH, 4);                    // May 2026 (15th)
            newLog(userId, action.id, LocalDate.of(2026, 1, 5), 1);      // Jan 2026
            newLog(userId, action.id, LocalDate.of(2025, 12, 31), 10);   // Dec 2025
            newLog(userId, action.id, LocalDate.of(2025, 7, 1), 1);      // Jul 2025
        });

        final SubjectStats stats = statsService.forAllSubjects(userId).getFirst();
        assertThat(stats.totalCount())
            .as("total count")
            .isEqualTo(21L);
        assertThat(stats.totalDays())
            .as("distinct logged days")
            .isEqualTo(6);
        assertThat(stats.firstPerformed())
            .as("first performed")
            .isEqualTo(LocalDate.of(2025, 7, 1));
        assertThat(stats.lastPerformed())
            .as("last performed")
            .isEqualTo(TODAY);
        assertThat(stats.thisMonthCount())
            .as("this month (Jun 2026) = 2+3")
            .isEqualTo(5L);
        assertThat(stats.lastMonthCount())
            .as("last month (May 2026)")
            .isEqualTo(4L);
        assertThat(stats.thisYearCount())
            .as("this year (2026) = 5+4+1")
            .isEqualTo(10L);
        assertThat(stats.lastYearCount())
            .as("last year (2025) = 10+1")
            .isEqualTo(11L);
        assertThat(stats.bestDay())
            .as("busiest single day is the 31st of December 2025")
            .isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(stats.bestDayCount())
            .as("busiest single day's count")
            .isEqualTo(10L);
        assertThat(stats.bestMonth())
            .as("best month is Dec 2025")
            .isEqualTo(YearMonth.of(2025, 12));
        assertThat(stats.bestMonthCount())
            .as("best month total")
            .isEqualTo(10L);
        assertThat(stats.bestYearLabel())
            .as("best year is 2025")
            .isEqualTo("2025");
        assertThat(stats.bestYearCount())
            .as("best year total")
            .isEqualTo(11L);
        assertThat(Durations.days(stats.currentStreak()))
            .as("only today in the current run")
            .isEqualTo(1);
        assertThat(stats.currentStreak().start())
            .as("the current run starts today")
            .isEqualTo(TODAY);
    }

    @Test
    void forAllSubjects_busiestDayTiedAcrossDays_reportsTheEarliestOfThem() {
        // A record is dated to when it was SET, not to when it was most recently equalled, so an equal count on a
        // later day must not move the figure forward.
        runInTx(() -> {
            final Action action = newAction(userId, "Tied");
            newLog(userId, action.id, LocalDate.of(2026, 3, 4), 5);
            newLog(userId, action.id, LocalDate.of(2026, 4, 4), 5);
            newLog(userId, action.id, LocalDate.of(2026, 5, 4), 2);
        });

        final SubjectStats stats = statsService.forAllSubjects(userId).getFirst();
        assertThat(stats.bestDay())
            .as("the earliest day holding the joint-highest count")
            .isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(stats.bestDayCount())
            .as("unexpected value")
            .isEqualTo(5L);
    }
}
