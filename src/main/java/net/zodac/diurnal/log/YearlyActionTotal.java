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
 * The database-side yearly aggregation of a subject's logs: the summed {@code count} for one subject within one calendar year. Produced by
 * {@link ActionLog#yearlyTotalsForActions(UUID, java.util.Collection, java.time.LocalDate, java.time.LocalDate)} (one instance per
 * {@code (subject, calendar-year)} in the window that has at least one entry) and consumed by the frequency chart to draw the all-time window as one
 * bar per year. A typed projection, never a positional {@code Object[]} tuple.
 *
 * <p>
 * Rolled up by the database rather than summed from {@link MonthlyActionTotal} rows in Java: the all-time window spans every year the account has
 * ever logged in, so folding months into years here would read twelve rows for each one it drew - the same shape the monthly rollup was bounded to
 * avoid (see {@code ActionLogQueries.MONTHLY_TOTALS_JPQL}).
 *
 * @param actionId the action the total belongs to
 * @param year the calendar year
 * @param total the summed {@code count} across that year
 */
public record YearlyActionTotal(UUID actionId, int year, long total) {
}
