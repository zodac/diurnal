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
import java.util.UUID;

/**
 * The database-side daily aggregation of an action's logs: the summed {@code count} for one action on one calendar day. Produced by
 * {@link ActionLog#dailyTotalsForActions(UUID, java.util.Collection, LocalDate, LocalDate)} (one instance per {@code (action, logged-day)} in the
 * requested window) and consumed by the Stats page's frequency chart to draw a month of daily bars without hydrating every log row. A typed
 * projection, never a positional {@code Object[]} tuple.
 *
 * @param actionId the action the total belongs to
 * @param date the calendar day the total belongs to
 * @param total the summed {@code count} on that day
 */
public record DailyActionTotal(UUID actionId, LocalDate date, long total) {
}
