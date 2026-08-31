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
 * The database-side monthly aggregation of a subject's logs: the summed {@code count} for one subject within one calendar month. Produced by
 * {@link ActionLog#monthlyTotalsForActions(UUID, java.util.Collection, java.time.LocalDate, java.time.LocalDate)} (one instance per
 * {@code (subject, calendar-month)} in the window that has at least one entry) and consumed by the frequency chart to draw a year as twelve monthly
 * bars. A typed projection in place of the previous positional {@code Object[]} tuple.
 *
 * <p>
 * The Stats page's per-subject totals and best-month/best-year figures no longer come from here: a month's total is the sum of its days' totals, so
 * {@code StatsService.assemble} derives them from the daily rollup it has already read rather than aggregating the same history a second time.
 *
 * @param actionId the action the total belongs to
 * @param year the calendar year of the month
 * @param month the calendar month ({@code 1}–{@code 12})
 * @param total the summed {@code count} across that month
 */
public record MonthlyActionTotal(UUID actionId, int year, int month, long total) {
}
