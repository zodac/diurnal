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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import net.zodac.diurnal.stub.StubAppConfig;
import org.junit.jupiter.api.Test;

/**
 * {@link AppClock#zoneFor(String)} - the resolution every user-visible date boundary passes through, and the one part of the clock that is pure
 * enough to pin without a container.
 *
 * <p>
 * A stored timezone is sanitised on write, so the fallbacks here are defensive: what they protect is a row written before a value was validated, or
 * one edited in the database by hand. Landing on the server-default zone keeps such an account working - resolving to a date at all - rather than
 * failing its every request with a {@link java.time.DateTimeException}.
 */
class AppClockTest {

    private static final ZoneId SERVER_DEFAULT_ZONE = ZoneId.of("UTC");

    private final AppClock clock = new AppClock(StubAppConfig.empty());

    @Test
    void zone_isTheConfiguredZone() {
        assertThat(clock.zone())
            .as("the clock is built from app.timezone, which the stub fixes to UTC")
            .isEqualTo(SERVER_DEFAULT_ZONE);
    }

    @Test
    void zoneFor_aValidZoneId_isThatZone() {
        assertThat(clock.zoneFor("Pacific/Auckland"))
            .as("a user's stored timezone is what their day boundary is measured in")
            .isEqualTo(ZoneId.of("Pacific/Auckland"));
    }

    @Test
    void zoneFor_anOffsetId_isThatOffset() {
        assertThat(clock.zoneFor("+05:30"))
            .as("a fixed-offset id is as valid a zone as a named one")
            .isEqualTo(ZoneId.of("+05:30"));
    }

    @Test
    void zoneFor_null_isTheServerDefaultZone() {
        assertThat(clock.zoneFor(null))
            .as("an account that has never set a timezone falls back to the server's")
            .isEqualTo(SERVER_DEFAULT_ZONE);
    }

    @Test
    void zoneFor_blank_isTheServerDefaultZone() {
        assertThat(clock.zoneFor("   "))
            .as("a blank stored value names no zone, so it falls back to the server's")
            .isEqualTo(SERVER_DEFAULT_ZONE);
    }

    @Test
    void zoneFor_anUnknownZoneId_isTheServerDefaultZone() {
        assertThat(clock.zoneFor("Mars/Olympus_Mons"))
            .as("an unrecognised stored value must fall back rather than throw, or the account's every request fails")
            .isEqualTo(SERVER_DEFAULT_ZONE);
    }

    @Test
    void zoneFor_aMalformedZoneId_isTheServerDefaultZone() {
        // ZoneId.of throws DateTimeException for a malformed id just as it does for an unknown region, and both must land on the same fallback.
        assertThat(clock.zoneFor("not a zone"))
            .as("a malformed stored value must fall back rather than throw")
            .isEqualTo(SERVER_DEFAULT_ZONE);
    }
}
