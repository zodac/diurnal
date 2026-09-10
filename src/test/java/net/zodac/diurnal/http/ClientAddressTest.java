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

package net.zodac.diurnal.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClientAddress}, which resolves the client IP behind the per-IP auth throttle and the security logging. Cloudflare's
 * {@code CF-Connecting-IP} is preferred because it is unspoofable through Cloudflare, with the socket-level remote address as the fallback for a
 * deployment not behind Cloudflare; the forwarded-header summary reduces hostile header values to printable ASCII so a log line cannot be forged.
 */
class ClientAddressTest {

    private static final String CLOUDFLARE_IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final String REMOTE_IP = "10.0.0.1"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final String FORWARDED_FOR_IP = "198.51.100.9"; // NOPMD: AvoidUsingHardCodedIP - test IP

    @Test
    void resolve_prefersCloudflareHeaderOverRemoteAddress() {
        assertThat(ClientAddress.resolve(CLOUDFLARE_IP, REMOTE_IP))
            .as("the unspoofable Cloudflare header should win over the socket remote address")
            .isEqualTo(CLOUDFLARE_IP);
    }

    @Test
    void resolve_stripsWhitespaceFromCloudflareHeader() {
        assertThat(ClientAddress.resolve("  " + CLOUDFLARE_IP + "  ", null))
            .as("a padded Cloudflare header should be trimmed to the bare address")
            .isEqualTo(CLOUDFLARE_IP);
    }

    @Test
    void resolve_fallsBackToRemoteAddressWhenCloudflareHeaderAbsent() {
        assertThat(ClientAddress.resolve(null, REMOTE_IP))
            .as("with no Cloudflare header the socket remote address should be used")
            .isEqualTo(REMOTE_IP);
    }

    @Test
    void resolve_fallsBackToRemoteAddressWhenCloudflareHeaderBlank() {
        assertThat(ClientAddress.resolve("   ", REMOTE_IP))
            .as("a blank Cloudflare header should be ignored in favour of the remote address")
            .isEqualTo(REMOTE_IP);
    }

    @Test
    void resolve_returnsUnknownWhenNeitherSourceIsPresent() {
        assertThat(ClientAddress.resolve(null, null))
            .as("with neither source the resolver should report the address as unknown")
            .isEqualTo("unknown");
    }

    @Test
    void resolve_returnsUnknownWhenRemoteAddressBlankAndNoCloudflareHeader() {
        assertThat(ClientAddress.resolve(null, "  "))
            .as("a blank remote address with no Cloudflare header should be unknown")
            .isEqualTo("unknown");
    }

    @Test
    void forwardedSummary_rendersAllThreeHeaders() {
        assertThat(ClientAddress.forwardedSummary(CLOUDFLARE_IP, FORWARDED_FOR_IP + ", " + CLOUDFLARE_IP, "diurnal.example.com"))
            .as("the summary should carry all three forwarding headers verbatim when they are printable ASCII")
            .isEqualTo("cf=" + CLOUDFLARE_IP + " xff=" + FORWARDED_FOR_IP + ", " + CLOUDFLARE_IP + " xfh=diurnal.example.com");
    }

    @Test
    void forwardedSummary_marksAbsentHeadersWithDash() {
        assertThat(ClientAddress.forwardedSummary(null, "   ", null))
            .as("a null or blank header should render as a dash rather than as empty text")
            .isEqualTo("cf=- xff=- xfh=-");
    }

    @Test
    void forwardedSummary_replacesControlAndNonAsciiCharacters() {
        final String withControlChar = "a" + (char) 0x01 + "b";
        final String withNonAsciiChar = "caf" + (char) 0xE9;

        assertThat(ClientAddress.forwardedSummary(CLOUDFLARE_IP, withControlChar, withNonAsciiChar))
            .as("a control character and a non-ASCII character should each be reduced to a dot")
            .isEqualTo("cf=" + CLOUDFLARE_IP + " xff=a.b xfh=caf.");
    }

    @Test
    void forwardedSummary_boundsAnOverlongValue() {
        assertThat(ClientAddress.forwardedSummary("a".repeat(200), null, null))
            .as("a value longer than the 128-character bound should be truncated")
            .isEqualTo("cf=" + "a".repeat(128) + " xff=- xfh=-");
    }

    @Test
    void forwardedSummary_stripsSurroundingWhitespace() {
        assertThat(ClientAddress.forwardedSummary("  " + CLOUDFLARE_IP + "  ", null, null))
            .as("surrounding whitespace should be stripped before the value is rendered")
            .isEqualTo("cf=" + CLOUDFLARE_IP + " xff=- xfh=-");
    }
}
