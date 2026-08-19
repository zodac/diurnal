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

package net.zodac.diurnal.time;

import java.time.LocalDate;

/**
 * A run of whole days as a <strong>half-open</strong> date range: {@code start} is the first day in the run, {@code endExclusive} the day after the
 * last one. A run of zero days is {@code start == endExclusive}.
 *
 * <p>
 * Half-open (rather than a first/last pair) is what makes the range and its length agree without an off-by-one anywhere: the number of days is
 * exactly {@code DAYS.between(start, endExclusive)}, a one-day run is {@code [d, d+1)}, and an empty run is representable at all. A statistic that is
 * only a day COUNT cannot be rendered as a calendar duration - "31 days" is one month or one month and three days depending on which month it sat
 * in - so every day count that is shown to a user as a duration is carried as one of these, anchored on the dates it actually covered.
 *
 * <p>
 * A pure data carrier: the length and the years/months/days breakdown both live in {@link Durations} (PITest cannot hot-swap
 * mutants into a record, so logic held here would silently escape the mutation gate).
 *
 * @param start the first day in the run
 * @param endExclusive the day AFTER the last day in the run (equal to {@code start} for an empty run)
 */
public record DaySpan(
    LocalDate start,
    LocalDate endExclusive
) {
}
