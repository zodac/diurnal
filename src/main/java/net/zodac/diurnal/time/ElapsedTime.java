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

import java.time.Duration;
import java.time.Instant;

/**
 * The single place the age of a past {@link Instant} is worded for the UI ("Just now", "5 minutes ago", "3 hours ago", "12 days ago").
 *
 * <p>
 * Deliberately distinct from {@link Durations}, which words a {@link DaySpan} - a run of whole calendar days, where the month/year boundaries depend
 * on which months the run covered. An age is measured from an exact instant (a sign-in, a request) rather than from a date, so it is a plain
 * {@link Duration} truncated to the largest whole unit that fits, and it stops at days: the only ages shown to a user are bounded by the session
 * lifetime ({@code session.absolute-timeout}, 90 days by default), so a day count never grows to the point of needing a months/years breakdown.
 *
 * <p>
 * An instant in the future (clock skew between the server and the stored timestamp) is clamped to "Just now" rather than reported as a negative age.
 */
public final class ElapsedTime {

    private static final String MINUTE_UNIT = "minute";
    private static final String HOUR_UNIT = "hour";
    private static final String DAY_UNIT = "day";
    private static final String SUFFIX = " ago";
    private static final String JUST_NOW = "Just now";

    private ElapsedTime() {

    }

    /**
     * Words how long ago {@code then} was, relative to {@code now}: under a minute (or in the future) is {@code "Just now"}, then whole minutes
     * ({@code "5 minutes ago"}), whole hours ({@code "3 hours ago"}) and finally whole days ({@code "12 days ago"}). Every count is singular-aware
     * via {@link Durations#count(long, String)}, so nothing ever reads "1 days".
     *
     * @param then the past instant to measure from
     * @param now  the current instant
     * @return the worded age
     */
    public static String since(final Instant then, final Instant now) {
        final Duration elapsed = Duration.between(then, now);
        if (elapsed.toMinutes() < 1L) {
            return JUST_NOW;
        }
        if (elapsed.toHours() < 1L) {
            return Durations.count(elapsed.toMinutes(), MINUTE_UNIT) + SUFFIX;
        }
        if (elapsed.toDays() < 1L) {
            return Durations.count(elapsed.toHours(), HOUR_UNIT) + SUFFIX;
        }
        return Durations.count(elapsed.toDays(), DAY_UNIT) + SUFFIX;
    }
}
