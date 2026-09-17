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

package net.zodac.diurnal.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.text.TextOrdering;
import net.zodac.diurnal.user.ActionOrder;
import org.junit.jupiter.api.Test;

/**
 * The dashboard day panel's list order: that the selected day's count outranks the setting under every option, that each option orders what is left
 * the way it says, and that every option ends in the collated name so nothing is left to the order the database happened to return.
 */
class DayActionOrderingTest {

    private static final Locale ENGLISH_GB = Locale.forLanguageTag("en-GB");
    private static final LocalDate JUNE_FIRST = LocalDate.of(2026, 6, 1);

    @Test
    void alphabetical_ordersByCollatedNameWithinTheSameCount() {
        final LogWebResource.DayActionStatus zeta = status("Zeta", 0);
        final LogWebResource.DayActionStatus alpha = status("alpha", 0);
        final LogWebResource.DayActionStatus eclair = status("Éclair", 0);

        assertThat(sorted(ActionOrder.ALPHABETICAL, Map.of(), zeta, alpha, eclair))
            .as("the collator decides, so a lowercase name and an accented one sit where the language puts them, not where their code points do")
            .containsExactly("alpha", "Éclair", "Zeta");
    }

    @Test
    void mostLogged_ordersByTheTotalEverLoggedHighestFirst() {
        final LogWebResource.DayActionStatus rare = status("Rare", 0);
        final LogWebResource.DayActionStatus common = status("Common", 0);
        final LogWebResource.DayActionStatus middling = status("Middling", 0);
        final Map<UUID, ActionHistory> history = Map.of(
            rare.action().id, new ActionHistory(rare.action().id, 2L, JUNE_FIRST),
            common.action().id, new ActionHistory(common.action().id, 500L, JUNE_FIRST),
            middling.action().id, new ActionHistory(middling.action().id, 50L, JUNE_FIRST));

        assertThat(sorted(ActionOrder.MOST_LOGGED, history, rare, common, middling))
            .as("the total logged decides, not the name and not the day's count, which is 0 for all three here")
            .containsExactly("Common", "Middling", "Rare");
    }

    @Test
    void mostRecent_ordersByTheDayLastLoggedMostRecentFirst() {
        final LogWebResource.DayActionStatus stale = status("Stale", 0);
        final LogWebResource.DayActionStatus fresh = status("Fresh", 0);
        final LogWebResource.DayActionStatus middling = status("Middling", 0);
        final Map<UUID, ActionHistory> history = Map.of(
            // The totals are deliberately the inverse of the dates: the two orders must not be able to pass each other's test.
            stale.action().id, new ActionHistory(stale.action().id, 900L, JUNE_FIRST.minusYears(1L)),
            fresh.action().id, new ActionHistory(fresh.action().id, 1L, JUNE_FIRST),
            middling.action().id, new ActionHistory(middling.action().id, 50L, JUNE_FIRST.minusWeeks(1L)));

        assertThat(sorted(ActionOrder.MOST_RECENT, history, stale, fresh, middling))
            .as("the day last logged decides, and an action logged once yesterday outranks one logged 900 times a year ago")
            .containsExactly("Fresh", "Middling", "Stale");
    }

    @Test
    void everyOrder_putsTheDaysOwnCountAboveTheSetting() {
        final LogWebResource.DayActionStatus loggedToday = status("Zeta", 3);
        final LogWebResource.DayActionStatus theRest = status("Alpha", 0);
        final Map<UUID, ActionHistory> history = Map.of(
            theRest.action().id, new ActionHistory(theRest.action().id, 900L, JUNE_FIRST));

        for (final ActionOrder order : ActionOrder.values()) {
            assertThat(sorted(order, history, loggedToday, theRest))
                .as("an action logged today must float to the top under %s, whatever the setting would otherwise say", order)
                .containsExactly("Zeta", "Alpha");
        }
    }

    @Test
    void historyBasedOrders_putNeverLoggedActionsLastAndSortThoseByName() {
        final LogWebResource.DayActionStatus logged = status("Zeta", 0);
        final LogWebResource.DayActionStatus neverB = status("Beta", 0);
        final LogWebResource.DayActionStatus neverA = status("Alpha", 0);
        final Map<UUID, ActionHistory> history = Map.of(
            logged.action().id, new ActionHistory(logged.action().id, 1L, JUNE_FIRST));

        final List<String> byTotal = sorted(ActionOrder.MOST_LOGGED, history, neverB, logged, neverA);
        final List<String> byRecency = sorted(ActionOrder.MOST_RECENT, history, neverB, logged, neverA);

        assertThat(List.of(byTotal, byRecency))
            .as("an action with no history at all belongs under one that has, and its own kind falls back to the alphabet")
            .containsExactly(List.of("Zeta", "Alpha", "Beta"), List.of("Zeta", "Alpha", "Beta"));
    }

    @Test
    void historyBasedOrders_fallBackToTheNameWhenTheHistoryTies() {
        final LogWebResource.DayActionStatus zeta = status("Zeta", 0);
        final LogWebResource.DayActionStatus alpha = status("Alpha", 0);
        final Map<UUID, ActionHistory> history = Map.of(
            zeta.action().id, new ActionHistory(zeta.action().id, 7L, JUNE_FIRST),
            alpha.action().id, new ActionHistory(alpha.action().id, 7L, JUNE_FIRST));

        final List<String> byTotal = sorted(ActionOrder.MOST_LOGGED, history, zeta, alpha);
        final List<String> byRecency = sorted(ActionOrder.MOST_RECENT, history, zeta, alpha);

        assertThat(List.of(byTotal, byRecency))
            .as("two actions on the same figure must not be left in whatever order the database returned - the list would reshuffle between renders")
            .containsExactly(List.of("Alpha", "Zeta"), List.of("Alpha", "Zeta"));
    }

    private static List<String> sorted(final ActionOrder order, final Map<UUID, ActionHistory> history,
        final LogWebResource.DayActionStatus... statuses) {
        final Comparator<LogWebResource.DayActionStatus> comparator =
            DayActionOrdering.comparator(order, history, TextOrdering.byName(ENGLISH_GB));
        return Stream.of(statuses).sorted(comparator).map(status -> status.action().name).toList();
    }

    private static LogWebResource.DayActionStatus status(final String name, final int count) {
        final Action action = new Action();
        action.id = UUID.randomUUID();
        action.name = name;
        return new LogWebResource.DayActionStatus(action, count);
    }
}
