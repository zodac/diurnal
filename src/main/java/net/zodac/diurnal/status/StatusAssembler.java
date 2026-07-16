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

package net.zodac.diurnal.status;

import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Pure builder for the {@link SystemStatus} payload and its HTTP status code — the testable core of the status endpoint, kept out of the CDI/DB glue
 * in {@link StatusService} and {@link StatusResource} so every branch is unit-mutation-covered.
 */
final class StatusAssembler {

    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long MINUTES_PER_HOUR = 60L;

    private StatusAssembler() {

    }

    /**
     * Builds the status payload: liveness is always {@code UP}, readiness reflects the database reachability, and the uptime is the elapsed time
     * between {@code startedAt} and {@code now}.
     *
     * @param databaseReachable whether a live database round-trip succeeded
     * @param version           the running release version
     * @param startedAt         the recorded startup instant
     * @param now               the current instant to measure uptime against
     * @return the assembled {@link SystemStatus}
     */
    static SystemStatus assemble(final boolean databaseReachable, final String version, final Instant startedAt, final Instant now) {
        // Liveness is constant: if this code runs, the application is up and serving. Readiness tracks the one dependency, the database.
        final HealthState readiness = databaseReachable ? HealthState.UP : HealthState.DOWN;
        return new SystemStatus(HealthState.UP, readiness, version, formatUptime(Duration.between(startedAt, now)));
    }

    /**
     * Maps a status to its readiness-gated HTTP code: {@code 200} when readiness is {@code UP}, else {@code 503}.
     *
     * @param status the assembled status
     * @return {@code 200} when ready, {@code 503} when not
     */
    static int httpStatus(final SystemStatus status) {
        // The container HEALTHCHECK keys on the HTTP status, so a not-ready app (database unreachable) must report 503, not a 200 with a DOWN body.
        return status.readiness() == HealthState.UP
            ? Response.Status.OK.getStatusCode()
            : Response.Status.SERVICE_UNAVAILABLE.getStatusCode();
    }

    /**
     * Formats an uptime as {@code HH:mm:ss.SSS} with leading zero-value groups omitted (e.g. {@code 45.678} under a minute, {@code 12:05.030} under
     * an hour, {@code 3:12:45.678} beyond); a negative duration is clamped to zero.
     *
     * @param uptime the elapsed time since startup
     * @return the formatted uptime string
     */
    static String formatUptime(final Duration uptime) {
        final long totalMillis = Math.max(0L, uptime.toMillis());
        final long hours = totalMillis / MILLIS_PER_HOUR;
        final long minutes = totalMillis / MILLIS_PER_MINUTE % MINUTES_PER_HOUR;
        final long seconds = totalMillis / MILLIS_PER_SECOND % SECONDS_PER_MINUTE;
        final long millis = totalMillis % MILLIS_PER_SECOND;

        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d.%03d", hours, minutes, seconds, millis);
        }
        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%d:%02d.%03d", minutes, seconds, millis);
        }
        return String.format(Locale.ROOT, "%d.%03d", seconds, millis);
    }
}
