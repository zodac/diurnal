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

package net.zodac.diurnal.auth.lockout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Human-readable text for an auth lockout — shared by the JSON API ({@code 429} body) and the web login/registration banners, so every surface
 * phrases the lockout identically.
 *
 * <p>
 * The wording is deliberately <em>neutral</em> — "Too many failed attempts" — rather than naming logins or registrations: the lockout is one shared
 * per-IP counter that both flows feed, so attributing it to either would be inaccurate (15 failed registrations then a blocked login must not read
 * "too many failed logins") and would leak how the throttle works. It also discloses nothing about whether an account exists (a non-existent email
 * locks out and shows the same text).
 */
public final class LockoutMessages {

    private LockoutMessages() {

    }

    /**
     * The full sentence shown to a locked-out user on the JSON API, stating the <em>exact</em> whole seconds remaining on the lockout, e.g.
     * "Too many failed attempts. Please try again in 42 seconds.". The API always renders this in English — see
     * {@code net.zodac.diurnal.web.AppMessages}'s class Javadoc on why a direct Java call can never be locale-aware; the web surface instead passes
     * {@link #retrySeconds(Duration)} as template data and lets {@code login.html}/{@code register.html} resolve the translated sentence themselves
     * via {@code {msg:lockoutRetry(lockoutSeconds)}}.
     *
     * @param remaining how much of the lockout is left
     * @return the user-facing lockout message, in English
     */
    public static String retryMessage(final Duration remaining) {
        final long seconds = retrySeconds(remaining);
        return "Too many failed attempts. Please try again in " + seconds + (seconds == 1L ? " second." : " seconds.");
    }

    /**
     * The whole seconds remaining on a lockout, floored to at least one so neither surface ever reads "0 seconds" — matches the
     * {@code Retry-After}/{@code X-Lockout-Retry-After} header sent alongside it.
     *
     * @param remaining how much of the lockout is left
     * @return the floored whole seconds remaining
     */
    public static long retrySeconds(final Duration remaining) {
        return Math.max(1L, remaining.toSeconds());
    }

    /**
     * Renders a duration in plain, correctly-pluralised English, e.g. {@code PT15M} → "15 minutes", {@code PT1H30M} → "1 hour and 30 minutes",
     * {@code PT45S} → "45 seconds". Sub-second and non-positive durations collapse to "a moment".
     *
     * @param duration the duration to render
     * @return the human-readable form
     */
    public static String humanReadable(final Duration duration) {
        final long totalSeconds = duration.getSeconds();
        if (totalSeconds < 1L) {
            return "a moment";
        }

        final long hours = duration.toHours();
        final int minutes = duration.toMinutesPart();
        final int seconds = duration.toSecondsPart();

        final List<String> parts = new ArrayList<>();
        if (hours > 0L) {
            parts.add(unit(hours, "hour"));
        }
        if (minutes > 0) {
            parts.add(unit(minutes, "minute"));
        }
        if (seconds > 0) {
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
            return parts.getFirst();
        }

        if (size == 2) { // NOPMD: AvoidLiteralsInIfCondition - Fine here, need to determine 1 vs 2 vs more
            return parts.getFirst() + " and " + parts.get(1);
        }

        // Three parts (hours, minutes and seconds): "a, b and c".
        return parts.getFirst() + ", " + parts.get(1) + " and " + parts.get(2);
    }
}
