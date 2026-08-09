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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import net.zodac.diurnal.config.AppConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single source of "now" for all date-boundary business logic (streaks, the future-date guard, the dashboard's pre-selected day, admin timestamp
 * formatting).
 *
 * <p>
 * Every "today"/"now" that drives visible behaviour MUST go through this bean rather than calling {@link LocalDate#now()} / {@link Instant#now()}
 * directly, so a test can freeze time and exercise edge cases (midnight rollover, non-UTC zones) deterministically. The clock is built from
 * {@code app.timezone}, which is the zone {@link #zone()} reports and the fallback {@link #zoneFor(String)} lands on.
 *
 * <p>
 * There is deliberately no zero-argument {@code today()}: every date boundary the user can see belongs to <em>their</em> configured timezone, so a
 * caller resolves it as {@code today(zoneFor(user.timezone))}. A server-default-zone overload would read as the obvious thing to call and would
 * silently be wrong for every user who has set a timezone.
 *
 * <p>
 * Entity audit timestamps ({@code createdAt}/{@code updatedAt}/{@code lastLoginAt}) deliberately stay on plain {@link Instant#now()} — they are
 * zone-independent and not date-boundary sensitive, so routing them through here would buy no determinism.
 */
@ApplicationScoped
public class AppClock {

    private static final Logger LOGGER = LogManager.getLogger(AppClock.class);

    private final AppConfig appConfig;

    private Clock clock;

    /**
     * Injects the application config and builds the system clock for the configured zone.
     *
     * @param appConfig the application config supplying {@code app.timezone}
     */
    @Inject
    public AppClock(final AppConfig appConfig) {
        this.appConfig = appConfig;
        this.clock = Clock.system(ZoneId.of(appConfig.timezone()));
    }

    /**
     * The configured zone all "today" comparisons are made in.
     */
    public ZoneId zone() {
        return clock.getZone();
    }

    /**
     * The current instant (zone-independent).
     */
    public Instant now() {
        return clock.instant();
    }

    /**
     * "Today" in an explicit zone — used for per-user timezone overrides.
     */
    public LocalDate today(final ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    /**
     * Resolve a stored timezone id to a zone, falling back to the server-default zone when it is null/blank or not a valid {@link ZoneId} (defensive
     * — stored values are sanitised on write).
     */
    public ZoneId zoneFor(@Nullable final String timezoneId) {
        if (timezoneId == null || timezoneId.isBlank()) {
            return clock.getZone();
        }
        try {
            return ZoneId.of(timezoneId);
        } catch (final DateTimeException e) {
            LOGGER.debug("Stored timezone '{}' is not a valid zone ID ({}) - falling back to the server-default zone {}",
                timezoneId, e.getMessage(), clock.getZone());
            return clock.getZone();
        }
    }

    // ── Test seam ─────────────────────────────────────────────────────────────
    // Only tests call these. They let an @QuarkusTest freeze time on the shared bean;
    // production code never mutates the clock.

    /**
     * Freeze time to a fixed clock (test-only).
     */
    public void useFixedClock(final Clock fixed) {
        this.clock = fixed;
    }

    /**
     * Restore the real system clock in the configured zone (test-only).
     */
    public void useSystemClock() {
        this.clock = Clock.system(ZoneId.of(appConfig.timezone()));
    }
}
