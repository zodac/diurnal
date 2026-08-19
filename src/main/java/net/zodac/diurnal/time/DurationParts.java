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

/**
 * A {@link DaySpan} measured into its calendar years/months/days breakdown ({@link Durations#breakdown(DaySpan)}) - the raw figures behind a
 * worded duration like {@code "1 year, 1 month, 17 days"}, deliberately carried as three plain numbers rather than pre-composed text.
 *
 * <p>
 * A Java call can never be locale-aware (see {@code web.AppMessages}' class Javadoc), so the WORDING - which components to mention, their plural
 * form, their order and separator - is entirely the job of {@code AppMessages#duration(long, long, long)}, resolved inside a template render. This
 * record only carries the measurement.
 *
 * @param years the whole years in the span
 * @param months the whole months remaining after the years (always {@code < 12})
 * @param days the whole days remaining after the months (always less than a calendar month)
 */
public record DurationParts(
    long years,
    long months,
    long days
) {
}
