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

import java.util.UUID;

/**
 * The database-side monthly aggregation of an action's logs: the summed {@code count} for one action within one calendar month. Produced by
 * {@link ActionLog#monthlyTotalsForActions(UUID, java.util.Collection)} (one instance per {@code (action, calendar-month)} that has at least one log
 * entry) and consumed by the Stats page to build per-action totals and best-month/best-year figures without hydrating every log row. A typed
 * projection in place of the previous positional {@code Object[]} tuple.
 *
 * @param actionId the action the total belongs to
 * @param year the calendar year of the month
 * @param month the calendar month ({@code 1}–{@code 12})
 * @param total the summed {@code count} across that month
 */
public record MonthlyActionTotal(UUID actionId, int year, int month, long total) {
}
