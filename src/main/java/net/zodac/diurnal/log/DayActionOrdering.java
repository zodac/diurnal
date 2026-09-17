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

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.user.ActionOrder;
import org.jspecify.annotations.Nullable;

/**
 * The one place the dashboard's day panel decides what order to list a user's actions in - the rule behind the "Dashboard action order" setting
 * ({@link ActionOrder}).
 *
 * <p>
 * <strong>The selected day's own count is the primary key under every option</strong>, highest first, so an action already logged that day sits at
 * the top of the panel whichever order is chosen. That is what keeps the panel a record of the day as well as the means of filling it in. The
 * setting replaces only what breaks a tie between two actions on the same count - which, on a day nothing has been logged against yet, is every pair
 * of them, so the setting decides the whole list exactly when it matters.
 *
 * <p>
 * <strong>Every order ends in the collated name</strong>, including the two that do not start there. A total and a last-logged day are both values
 * many actions share (most obviously the actions with neither), and a comparator that stopped at one would leave those in whatever order the
 * database handed them back - stable within a render and different across two, which reads as a list that reshuffles itself. The name is the only
 * key that is total, and it is COLLATED rather than compared by code point for the reason {@code ActionsInternalResource#getActions} gives: code
 * point order puts every accented or non-Latin name after every plain-ASCII one, and would order the same two names differently on two surfaces of
 * the same screen.
 *
 * <p>
 * A never-logged action has no {@link ActionHistory} entry at all, and sorts to the bottom of both history-based orders - below every action with a
 * history, and among its own kind by name. Held as a pure static so the orderings are unit-testable ({@code DayActionOrderingTest}); the resource
 * that uses it can only be reached through a rendered page.
 */
final class DayActionOrdering {

    // Below every real total and every real date: a never-logged action has no history row, and belongs under one that has.
    private static final long NO_HISTORY_TOTAL = -1L;
    private static final LocalDate NO_HISTORY_DATE = LocalDate.MIN;

    private DayActionOrdering() {

    }

    /**
     * Builds the comparator the day panel's list is sorted with.
     *
     * @param order the account's chosen order, which decides the tie-break between two actions on the same count for the day
     * @param history each logged action's whole-history summary, keyed by action id, as {@link ActionLog#historyByAction(UUID)} returns it; empty
     *     for {@link ActionOrder#ALPHABETICAL}, which needs none of it
     * @param byName the collator for the viewing user's own language
     * @return the comparator, highest count for the day first
     */
    static Comparator<LogWebResource.DayActionStatus> comparator(final ActionOrder order, final Map<UUID, ActionHistory> history,
        final Comparator<String> byName) {
        final Comparator<LogWebResource.DayActionStatus> byActionName =
            Comparator.comparing((LogWebResource.DayActionStatus status) -> status.action().name, byName);
        final Comparator<LogWebResource.DayActionStatus> tieBreak = switch (order) {
            case ALPHABETICAL -> byActionName;
            case MOST_LOGGED -> Comparator.comparingLong((LogWebResource.DayActionStatus status) -> totalFor(history, status))
            .reversed()
            .thenComparing(byActionName);
            case MOST_RECENT -> Comparator.comparing((LogWebResource.DayActionStatus status) -> lastLoggedFor(history, status))
            .reversed()
            .thenComparing(byActionName);
        };

        return Comparator.comparingInt(LogWebResource.DayActionStatus::count)
            .reversed()
            .thenComparing(tieBreak);
    }

    private static long totalFor(final Map<UUID, ActionHistory> history, final LogWebResource.DayActionStatus status) {
        final @Nullable ActionHistory entry = history.get(status.action().id);
        return entry == null ? NO_HISTORY_TOTAL : entry.totalCount();
    }

    private static LocalDate lastLoggedFor(final Map<UUID, ActionHistory> history, final LogWebResource.DayActionStatus status) {
        final @Nullable ActionHistory entry = history.get(status.action().id);
        return entry == null ? NO_HISTORY_DATE : entry.lastLogged();
    }
}
