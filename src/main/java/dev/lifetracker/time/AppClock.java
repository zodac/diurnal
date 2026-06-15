package dev.lifetracker.time;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The single source of "now" for all date-boundary business logic (streaks, the future-date
 * guard, the dashboard's pre-selected day, admin timestamp formatting).
 *
 * <p>
 * Every "today"/"now" that drives visible behaviour MUST go through this bean rather than
 * calling {@link LocalDate#now()} / {@link Instant#now()} directly, so a test can freeze time
 * and exercise edge cases (midnight rollover, non-UTC zones) deterministically. The clock is
 * built from {@code app.timezone}, so {@link #today()} is always "today in the configured zone".
 *
 * <p>
 * Entity audit timestamps ({@code createdAt}/{@code updatedAt}/{@code lastLoginAt}) deliberately
 * stay on plain {@link Instant#now()} — they are zone-independent and not date-boundary sensitive,
 * so routing them through here would buy no determinism.
 */
@ApplicationScoped
public class AppClock {

    @ConfigProperty(name = "app.timezone", defaultValue = "UTC")
    String timezoneId;

    private Clock clock;

    @PostConstruct
    void init() {
        clock = Clock.system(ZoneId.of(timezoneId));
    }

    /** The configured zone all "today" comparisons are made in. */
    public ZoneId zone() {
        return clock.getZone();
    }

    /** The current instant (zone-independent). */
    public Instant now() {
        return clock.instant();
    }

    /** "Today" in the configured (server-default) zone. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** "Today" in an explicit zone — used for per-user timezone overrides. */
    public LocalDate today(ZoneId zone) {
        return LocalDate.now(clock.withZone(zone));
    }

    /**
     * Resolve a stored timezone id to a zone, falling back to the server-default zone when it is
     * null/blank or not a valid {@link ZoneId} (defensive — stored values are sanitised on write).
     */
    public ZoneId zoneFor(String timezoneId) {
        if (timezoneId == null || timezoneId.isBlank()) {
            return clock.getZone();
        }
        try {
            return ZoneId.of(timezoneId);
        } catch (DateTimeException e) {
            return clock.getZone();
        }
    }

    // ── Test seam ─────────────────────────────────────────────────────────────
    // Only tests call these. They let an @QuarkusTest freeze time on the shared bean;
    // production code never mutates the clock.

    /** Freeze time to a fixed clock (test-only). */
    public void useFixedClock(Clock fixed) {
        this.clock = fixed;
    }

    /** Restore the real system clock in the configured zone (test-only). */
    public void useSystemClock() {
        this.clock = Clock.system(ZoneId.of(timezoneId));
    }
}
