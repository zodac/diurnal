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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionTokens}: token minting, hashing, the validity boundaries and the last-used bump-coalescing predicate.
 */
class SessionTokensTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final Duration IDLE = Duration.ofDays(30L);

    @Test
    void generate_producesUnpaddedUrlSafeToken() {
        final String token = SessionTokens.generate();
        assertThat(token)
                .as("32 random bytes must base64url-encode to 43 unpadded characters")
                .hasSize(43)
                .doesNotContain("=")
                .doesNotContain("+")
                .doesNotContain("/");
    }

    @Test
    void generate_isDistinctEachCall() {
        assertThat(SessionTokens.generate())
                .as("Each generated token must be unique")
                .isNotEqualTo(SessionTokens.generate());
    }

    @Test
    void hash_isDeterministicAndThirtyTwoBytes() {
        final String token = "a-fixed-token";
        assertThat(SessionTokens.hash(token))
                .as("SHA-256 of the same token must be stable and 32 bytes")
                .hasSize(32)
                .isEqualTo(SessionTokens.hash(token));
    }

    @Test
    void hash_differsForDifferentTokens() {
        assertThat(SessionTokens.hash("token-one"))
                .as("Different tokens must hash differently")
                .isNotEqualTo(SessionTokens.hash("token-two"));
    }

    @Test
    void isValid_wellWithinBothBounds_isTrue() {
        final Instant expires = NOW.plus(Duration.ofDays(10L));
        assertThat(SessionTokens.isValid(NOW.minus(Duration.ofMinutes(1L)), expires, NOW, IDLE))
                .as("A recently-used, unexpired session must be valid")
                .isTrue();
    }

    @Test
    void isValid_atAbsoluteExpiryInstant_isFalse() {
        assertThat(SessionTokens.isValid(NOW, NOW, NOW, IDLE))
                .as("The absolute-expiry boundary instant must count as expired")
                .isFalse();
    }

    @Test
    void isValid_pastAbsoluteExpiry_isFalse() {
        final Instant expires = NOW.minus(Duration.ofSeconds(1L));
        assertThat(SessionTokens.isValid(NOW, expires, NOW, IDLE))
                .as("A session past its absolute expiry must be invalid even if recently used")
                .isFalse();
    }

    @Test
    void isValid_atIdleDeadline_isFalse() {
        final Instant lastUsed = NOW.minus(IDLE);
        final Instant expires = NOW.plus(Duration.ofDays(60L));
        assertThat(SessionTokens.isValid(lastUsed, expires, NOW, IDLE))
                .as("The idle deadline instant must count as expired")
                .isFalse();
    }

    @Test
    void isValid_pastIdleDeadline_isFalse() {
        final Instant lastUsed = NOW.minus(IDLE).minus(Duration.ofSeconds(1L));
        final Instant expires = NOW.plus(Duration.ofDays(60L));
        assertThat(SessionTokens.isValid(lastUsed, expires, NOW, IDLE))
                .as("A session idle beyond the timeout must be invalid even if under its absolute cap")
                .isFalse();
    }

    @Test
    void shouldBumpLastUsed_whenStalerThanInterval_isTrue() {
        final Instant lastUsed = NOW.minus(Duration.ofMinutes(2L));
        assertThat(SessionTokens.shouldBumpLastUsed(lastUsed, NOW, Duration.ofMinutes(1L)))
                .as("A last-used time older than the interval must trigger a bump")
                .isTrue();
    }

    @Test
    void shouldBumpLastUsed_atExactlyInterval_isFalse() {
        final Instant lastUsed = NOW.minus(Duration.ofMinutes(1L));
        assertThat(SessionTokens.shouldBumpLastUsed(lastUsed, NOW, Duration.ofMinutes(1L)))
                .as("At exactly the interval boundary no bump is needed")
                .isFalse();
    }

    @Test
    void shouldBumpLastUsed_withinInterval_isFalse() {
        final Instant lastUsed = NOW.minus(Duration.ofSeconds(10L));
        assertThat(SessionTokens.shouldBumpLastUsed(lastUsed, NOW, Duration.ofMinutes(1L)))
                .as("A recent last-used time within the interval must not trigger a bump")
                .isFalse();
    }
}
