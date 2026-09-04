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

import java.time.Clock;

/**
 * The test tree's handle on {@link AppClock}'s frozen-time seam.
 *
 * <p>
 * {@link AppClock#useFixedClock(Clock)} and {@link AppClock#useSystemClock()} are package-private, so that no production caller can swap the clock
 * out from under every user in the deployment. That leaves the test tree needing a way in, and this is it: the one class in
 * {@code net.zodac.diurnal.time} that exists purely to relay those two calls out to {@code IntegrationTestBase}, which sits in
 * {@code net.zodac.diurnal} and so cannot make them itself.
 *
 * <p>
 * Nothing else should call these. A test freezes time through {@code IntegrationTestBase}'s own {@code freezeInstant(Instant, ZoneId)} — which is
 * where {@code FIXED_TODAY} and the {@code @AfterEach} restore are handled — rather than reaching for this directly.
 */
public final class AppClocks {

    /**
     * Freezes {@code clock} to {@code fixed}, so a date boundary can be pinned and asserted on deterministically.
     *
     * @param clock the application clock to freeze
     * @param fixed the clock it should read from until {@link #restore(AppClock)} puts the real one back
     */
    public static void freeze(final AppClock clock, final Clock fixed) {
        clock.useFixedClock(fixed);
    }

    /**
     * Restores {@code clock} to the real system clock in the configured zone, undoing {@link #freeze(AppClock, Clock)}.
     *
     * @param clock the application clock to restore
     */
    public static void restore(final AppClock clock) {
        clock.useSystemClock();
    }

    private AppClocks() {

    }
}
