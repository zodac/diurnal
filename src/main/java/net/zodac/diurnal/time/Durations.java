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
import java.util.ArrayList;
import java.util.List;

/**
 * The single place a {@link DaySpan} is measured and turned into a human-readable duration ("5 days", "1 month", "2 years, 3 months, 1 day"), plus
 * the project's one pluralisation rule.
 *
 * <p>
 * Every user-facing day count that can grow past a month (streaks, gaps) is rendered through {@link #label(DaySpan)} so the wording is identical
 * wherever it appears - a bare "412 days" is unreadable, and hand-rolling the breakdown per call site drifts (most obviously into "1 months").
 *
 * <p>
 * The month/year boundaries are <strong>calendar</strong> boundaries, not fixed 30/365-day buckets: a month is counted only once the day-of-month has
 * been reached (16 January -> 16 February is "1 month"; 16 February -> 17 April is "2 months, 1 day"), and a year only once the day-of-year has, so a
 * leap day is never dropped. Because that arithmetic depends on WHICH months a run covered - the same 31 days is "1 month" in one place in the
 * calendar and "1 month, 3 days" in another - the input is a real date range rather than a day count. A given span therefore always renders the same
 * way, and keeps rendering the same way as time passes.
 */
public final class Durations {

    private static final String YEAR_UNIT = "year";
    private static final String MONTH_UNIT = "month";
    private static final String DAY_UNIT = "day";
    private static final String SEPARATOR = ", ";

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
     * Renders a span as a human-readable duration: plain days ({@code "5 days"}) while it stays under a calendar month, otherwise the
     * years/months/days breakdown with every zero component omitted ({@code "1 month"}, {@code "2 months, 1 day"}, {@code "1 year, 17 days"}). Each
     * component is singular-aware, so a lone month reads "1 month" and never "1 months". An empty span is {@code "0 days"}.
     *
     * @param span the span to render
     * @return the duration label
     */
    public static String label(final DaySpan span) {
        if (!exceedsOneMonth(span)) {
            return count(days(span), DAY_UNIT);
        }

        final Period period = period(span);
        final List<String> parts = new ArrayList<>(3);
        if (period.getYears() > 0) {
            parts.add(count(period.getYears(), YEAR_UNIT));
        }
        if (period.getMonths() > 0) {
            parts.add(count(period.getMonths(), MONTH_UNIT));
        }
        if (period.getDays() > 0) {
            parts.add(count(period.getDays(), DAY_UNIT));
        }
        return String.join(SEPARATOR, parts);
    }

    /**
     * Whether a span reaches at least one calendar month, i.e. whether {@link #label(DaySpan)} renders a years/months/days breakdown rather than
     * plain days.
     *
     * @param span the span to test
     * @return {@code true} if the span reaches a calendar month
     */
    public static boolean exceedsOneMonth(final DaySpan span) {
        return period(span).toTotalMonths() > 0;
    }

    /**
     * A count with its singular-aware unit: {@code "1 day"}, {@code "5 days"}.
     *
     * @param count the count
     * @param unit the singular unit word
     * @return the count and its unit
     */
    public static String count(final long count, final String unit) {
        return count + " " + plural(count, unit);
    }

    /**
     * The singular or plural form of a unit word for the given count - the project's one pluralisation rule, so no UI text ever reads "1 days".
     *
     * @param count the count the unit describes
     * @param unit the singular unit word
     * @return {@code unit} when {@code count} is exactly one, else {@code unit + "s"}
     */
    public static String plural(final long count, final String unit) {
        return count == 1 ? unit : unit + "s";
    }

    private static Period period(final DaySpan span) {
        // A reversed span is nonsense (and would yield a negative Period); collapse it to an empty one so a
        // caller cannot render "-1 days" or an inverted breakdown.
        return span.endExclusive().isBefore(span.start())
                ? Period.ZERO
                : Period.between(span.start(), span.endExclusive());
    }
}
