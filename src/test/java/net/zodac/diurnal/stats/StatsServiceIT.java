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
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link StatsService#forMostRecent(UUID, int)} — the dashboard summary path.
 *
 * <p>
 * These assert observable behaviour only (which actions are returned, in what order, and that the
 * stats reflect the actions' <em>full</em> history), so they hold regardless of whether the selection
 * is done in Java or pushed into SQL.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // userId populated in createDbState(), called from the base @BeforeEach
class StatsServiceIT extends IntegrationTestBase {

    private static final LocalDate TODAY = FIXED_TODAY;              // 2026-06-15
    private static final LocalDate MONTH_START = TODAY.withDayOfMonth(1);
    private static final LocalDate LAST_MONTH = TODAY.minusMonths(1);

    @Inject
    StatsService statsService;

    UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser("stats-svc@lt.test", "Stats Service User").id;
    }

    private List<String> recentNames(final int limit) {
        return statsService.forMostRecent(userId, limit).stream()
                .map(s -> s.action().name)
                .toList();
    }

    @Test
    void forMostRecent_excludesActionsNotPerformedThisMonth() {
        runInTx(() -> {
            final Action thisMonth = newAction(userId, "ThisMonth");
            newLog(userId, thisMonth.id, TODAY, 1);
            final Action lastMonthOnly = newAction(userId, "LastMonthOnly");
            newLog(userId, lastMonthOnly.id, LAST_MONTH, 1);
        });

        assertThat(recentNames(10))
                .as("only actions performed in the current month should appear")
                .containsExactly("ThisMonth");
    }

    @Test
    void forMostRecent_ordersByMostRecentlyPerformedFirst() {
        // Names are deliberately in the opposite order to the performed-dates, so a name-ascending
        // result would differ from the expected recency ordering.
        runInTx(() -> {
            final Action alpha = newAction(userId, "Alpha");
            newLog(userId, alpha.id, MONTH_START.plusDays(4), 1);   // performed 5th
            final Action bravo = newAction(userId, "Bravo");
            newLog(userId, bravo.id, TODAY.minusDays(1), 1);         // performed 14th (most recent)
            final Action charlie = newAction(userId, "Charlie");
            newLog(userId, charlie.id, MONTH_START.plusDays(9), 1);  // performed 10th
        });

        assertThat(recentNames(10))
                .as("actions should be ordered most-recently-performed first")
                .containsExactly("Bravo", "Charlie", "Alpha");
    }

    @Test
    void forMostRecent_tiesBrokenByNameAscending() {
        runInTx(() -> {
            final Action zebra = newAction(userId, "Zebra");
            newLog(userId, zebra.id, TODAY, 1);
            final Action apple = newAction(userId, "Apple");
            newLog(userId, apple.id, TODAY, 1);
        });

        assertThat(recentNames(10))
                .as("actions performed on the same day should be ordered by name")
                .containsExactly("Apple", "Zebra");
    }

    @Test
    void forMostRecent_respectsLimit() {
        runInTx(() -> {
            for (int day = 1; day <= 4; day++) {
                final Action action = newAction(userId, "Action" + day);
                newLog(userId, action.id, MONTH_START.plusDays(day), 1);
            }
        });

        // Days 4, 3 are the two most recent — Action4 then Action3.
        assertThat(recentNames(2))
                .as("only the most-recent `limit` actions should be returned")
                .containsExactly("Action4", "Action3");
    }

    @Test
    void forMostRecent_computesStatsOverFullHistoryNotJustThisMonth() {
        runInTx(() -> {
            final Action action = newAction(userId, "LongHistory");
            newLog(userId, action.id, TODAY, 2);                    // this month
            newLog(userId, action.id, TODAY.minusMonths(2), 3);     // April — outside the month window
        });

        final List<ActionStats> stats = statsService.forMostRecent(userId, 10);
        assertThat(stats)
                .as("the action performed this month should be present")
                .hasSize(1);
        assertThat(stats.getFirst().totalCount())
                .as("total count must reflect the action's full history, not just this month")
                .isEqualTo(5L);
        assertThat(stats.getFirst().totalDays())
                .as("total distinct days must reflect the action's full history")
                .isEqualTo(2);
    }

    @Test
    void forAllActiveActions_excludesUnloggedActionsAndOrdersByName() {
        runInTx(() -> {
            newAction(userId, "Zeta");                                   // no logs — excluded
            final Action logged = newAction(userId, "Beta");
            newLog(userId, logged.id, TODAY, 1);
            newAction(userId, "Alpha");                                  // no logs — excluded
        });

        assertThat(statsService.forAllActiveActions(userId).stream().map(s -> s.action().name).toList())
                .as("only actions with logged data should appear")
                .containsExactly("Beta");
    }

    @Test
    void forAllActiveActions_aggregatesMonthlyAndYearlyTotalsFromSql() {
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

        final ActionStats stats = statsService.forAllActiveActions(userId).getFirst();
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
        assertThat(stats.bestMonthLabel())
            .as("best month is Dec 2025")
            .isEqualTo("December 2025");
        assertThat(stats.bestMonthCount())
            .as("best month total")
            .isEqualTo(10L);
        assertThat(stats.bestYearLabel())
            .as("best year is 2025")
            .isEqualTo("2025");
        assertThat(stats.bestYearCount())
            .as("best year total")
            .isEqualTo(11L);
        assertThat(stats.currentStreak())
            .as("only today in the current run")
            .isEqualTo(1);
    }

    @Test
    void forMostRecent_emptyWhenNothingLoggedThisMonth() {
        runInTx(() -> {
            final Action action = newAction(userId, "Stale");
            newLog(userId, action.id, LAST_MONTH, 1);
        });

        assertThat(statsService.forMostRecent(userId, 10))
                .as("no actions performed this month should yield an empty result")
                .isEmpty();
    }
}
