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

import java.time.Period;
import java.time.temporal.ChronoUnit;

/**
 * The single place a {@link DaySpan} is measured, both as a plain day count and as its calendar years/months/days breakdown.
 *
 * <p>
 * The month/year boundaries are <strong>calendar</strong> boundaries, not fixed 30/365-day buckets: a month is counted only once the day-of-month has
 * been reached (16 January -> 16 February is "1 month"; 16 February -> 17 April is "2 months, 1 day"), and a year only once the day-of-year has, so a
 * leap day is never dropped. Because that arithmetic depends on WHICH months a run covered - the same 31 days is "1 month" in one place in the
 * calendar and "1 month, 3 days" in another - the input is a real date range rather than a day count.
 *
 * <p>
 * Wording a breakdown into text ("5 days", "1 month", "2 years, 3 months, 1 day") is deliberately NOT this class's job - a Java call can never be
 * locale-aware (see {@code web.AppMessages}' class Javadoc), so every caller renders {@link #breakdown(DaySpan)}'s raw numbers through
 * {@code AppMessages#duration(long, long, long)} inside a template, never by composing English here.
 */
public final class Durations {

    private Durations() {

    }

    /**
     * The length of a span in whole days ({@code 0} for an empty span, and defensively for a reversed one).
     *
     * @param span the span to measure
     * @return the number of days in the span
     */
    public static int days(final DaySpan span) {
        return (int) Math.max(0L, ChronoUnit.DAYS.between(span.start(), span.endExclusive()));
    }

    /**
     * Measures a span into its calendar years/months/days breakdown, e.g. {@code (1, 1, 17)} for "1 year, 1 month, 17 days". A reversed span
     * measures as all-zero, the same as a genuinely empty one - both are nonsense to render as a negative duration.
     *
     * @param span the span to measure
     * @return the years/months/days breakdown
     */
    public static DurationParts breakdown(final DaySpan span) {
        final Period period = period(span);
        return new DurationParts(period.getYears(), period.getMonths(), period.getDays());
    }

    private static Period period(final DaySpan span) {
        // A reversed span is nonsense (and would yield a negative Period); collapse it to an empty one so a
        // caller cannot render "-1 days" or an inverted breakdown.
        return span.endExclusive().isBefore(span.start())
                ? Period.ZERO
                : Period.between(span.start(), span.endExclusive());
    }
}
