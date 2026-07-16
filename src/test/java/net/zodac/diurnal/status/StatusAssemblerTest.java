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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatusAssembler}: the readiness/liveness derivation, the readiness-gated HTTP status, and the {@code HH:mm:ss.SSS} uptime
 * formatting (including the omission of leading zero-value groups).
 */
class StatusAssemblerTest {

    private static final Instant STARTED_AT = Instant.parse("2026-06-15T00:00:00Z");

    @Test
    void assemble_databaseReachable_isLiveAndReady() {
        final SystemStatus status = StatusAssembler.assemble(true, "1.2.3", STARTED_AT, STARTED_AT.plusSeconds(5));

        assertThat(status.liveness())
            .as("liveness is always UP when the payload is produced")
            .isEqualTo(HealthState.UP);
        assertThat(status.readiness())
            .as("a reachable database means the app is ready")
            .isEqualTo(HealthState.UP);
        assertThat(status.version())
            .as("the supplied version must be carried through unchanged")
            .isEqualTo("1.2.3");
        assertThat(status.uptime())
            .as("uptime of five seconds should render as seconds.millis")
            .isEqualTo("5.000");
    }

    @Test
    void assemble_databaseUnreachable_isLiveButNotReady() {
        final SystemStatus status = StatusAssembler.assemble(false, "9.9.9", STARTED_AT, STARTED_AT.plusSeconds(1));

        assertThat(status.liveness())
            .as("liveness is still UP even when the database is unreachable")
            .isEqualTo(HealthState.UP);
        assertThat(status.readiness())
            .as("an unreachable database means the app is not ready")
            .isEqualTo(HealthState.DOWN);
    }

    @Test
    void httpStatus_readyReturns200_notReadyReturns503() {
        final SystemStatus ready = new SystemStatus(HealthState.UP, HealthState.UP, "1.0.0", "0.000");
        final SystemStatus notReady = new SystemStatus(HealthState.UP, HealthState.DOWN, "1.0.0", "0.000");

        assertThat(StatusAssembler.httpStatus(ready))
            .as("a ready app must report HTTP 200")
            .isEqualTo(200);
        assertThat(StatusAssembler.httpStatus(notReady))
            .as("a not-ready app must report HTTP 503 so the container HEALTHCHECK fails")
            .isEqualTo(503);
    }

    @Test
    void formatUptime_withHours_showsAllGroups() {
        final Duration uptime = Duration.ofHours(3).plusMinutes(12).plusSeconds(45).plusMillis(678);

        assertThat(StatusAssembler.formatUptime(uptime))
            .as("an uptime over an hour must render HH:mm:ss.SSS with zero-padded lower groups")
            .isEqualTo("3:12:45.678");
    }

    @Test
    void formatUptime_hoursWithZeroMinutes_stillZeroPadsMinutesAndSeconds() {
        final Duration uptime = Duration.ofHours(2).plusSeconds(3).plusMillis(1);

        assertThat(StatusAssembler.formatUptime(uptime))
            .as("hours present means minutes/seconds stay zero-padded even when zero")
            .isEqualTo("2:00:03.001");
    }

    @Test
    void formatUptime_underAnHour_omitsHours() {
        final Duration uptime = Duration.ofMinutes(12).plusSeconds(5).plusMillis(30);

        assertThat(StatusAssembler.formatUptime(uptime))
            .as("under an hour the hours group is omitted; seconds stay zero-padded")
            .isEqualTo("12:05.030");
    }

    @Test
    void formatUptime_underOneMinute_omitsHoursAndMinutes() {
        final Duration uptime = Duration.ofSeconds(45).plusMillis(678);

        assertThat(StatusAssembler.formatUptime(uptime))
            .as("under a minute both hours and minutes are omitted")
            .isEqualTo("45.678");
    }

    @Test
    void formatUptime_zero_rendersZeroSeconds() {
        assertThat(StatusAssembler.formatUptime(Duration.ZERO))
            .as("a zero duration renders as 0.000")
            .isEqualTo("0.000");
    }

    @Test
    void formatUptime_negative_isClampedToZero() {
        assertThat(StatusAssembler.formatUptime(Duration.ofSeconds(-5)))
            .as("a negative duration (clock skew) is clamped to zero rather than rendering a negative")
            .isEqualTo("0.000");
    }
}
