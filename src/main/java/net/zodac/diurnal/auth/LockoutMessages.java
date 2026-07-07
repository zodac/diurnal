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

package net.zodac.diurnal.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable text for a login lockout — shared by the JSON API ({@code 429} body) and the web
 * login banner, so both surfaces phrase the lockout identically.
 *
 * <p>
 * The message names the configured lockout window (e.g. "15 minutes"), not the exact remaining time:
 * it states the policy, and disclosing "too many attempts" reveals nothing about whether the account
 * exists (a non-existent email locks out and shows the same text).
 */
public final class LockoutMessages {

    private LockoutMessages() {

    }

    /**
     * The full sentence shown to a locked-out user, phrasing the time <em>remaining</em> on the lockout
     * approximately, e.g. "Too many failed login attempts. Please try again in about 12 minutes.".
     *
     * @param remaining how much of the lockout is left
     * @return the user-facing lockout message
     */
    public static String retryMessage(final Duration remaining) {
        return "Too many failed login attempts. Please try again in " + approximateRemaining(remaining) + '.';
    }

    /**
     * Renders a remaining lockout as a friendly, deliberately coarse phrase — "less than a minute" under
     * a minute, otherwise "about N minute(s)" rounded <em>up</em> so a user is never told to retry early.
     *
     * @param remaining the remaining lockout duration
     * @return the approximate remaining time
     */
    static String approximateRemaining(final Duration remaining) {
        final long seconds = remaining.getSeconds();
        if (seconds < 60L) {
            return "less than a minute";
        }
        final long minutes = (seconds + 59L) / 60L;
        return "about " + minutes + (minutes == 1L ? " minute" : " minutes");
    }

    /**
     * Renders a duration in plain, correctly-pluralised English, e.g. {@code PT15M} → "15 minutes",
     * {@code PT1H30M} → "1 hour and 30 minutes", {@code PT45S} → "45 seconds". Sub-second and
     * non-positive durations collapse to "a moment".
     *
     * @param duration the duration to render
     * @return the human-readable form
     */
    public static String humanReadable(final Duration duration) {
        final long totalSeconds = duration.getSeconds();
        if (totalSeconds < 1L) {
            return "a moment";
        }

        final long hours = totalSeconds / 3600L;
        final long minutes = totalSeconds % 3600L / 60L;
        final long seconds = totalSeconds % 60L;

        final List<String> parts = new ArrayList<>();
        if (hours > 0L) {
            parts.add(unit(hours, "hour"));
        }
        if (minutes > 0L) {
            parts.add(unit(minutes, "minute"));
        }
        if (seconds > 0L) {
            parts.add(unit(seconds, "second"));
        }
        return join(parts);
    }

    private static String unit(final long count, final String noun) {
        return count + " " + noun + (count == 1L ? "" : "s");
    }

    private static String join(final List<String> parts) {
        final int size = parts.size();
        if (size == 1) {
            return parts.get(0);
        }
        if (size == 2) {
            return parts.get(0) + " and " + parts.get(1);
        }
        // Three parts (hours, minutes and seconds): "a, b and c".
        return parts.get(0) + ", " + parts.get(1) + " and " + parts.get(2);
    }
}
