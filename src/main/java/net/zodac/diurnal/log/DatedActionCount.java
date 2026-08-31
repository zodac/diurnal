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
 * One stored log entry, read as the three columns anything actually uses: the day, the action, and the tally. Produced by
 * {@link ActionLog#findByUserAndRange(UUID, LocalDate, LocalDate)} for the calendar feeds, the dashboard's month back-fill and the day-panel rollup.
 * A typed projection, never a positional {@code Object[]} tuple.
 *
 * <p>
 * Distinct from {@link DailyActionTotal} despite the similar shape: that one is an <strong>aggregate</strong> ({@code SUM(count)} over a group,
 * hence its {@code long}), where this is the row as stored - a {@code (user, action, day)} entry is unique, so its {@code count} is the entry's own
 * {@code SMALLINT} and needs no summing.
 *
 * @param date the calendar day the entry was logged against
 * @param actionId the action that was logged
 * @param count how many times it was performed that day
 */
public record DatedActionCount(LocalDate date, UUID actionId, int count) {
}
