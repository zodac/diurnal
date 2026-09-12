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

import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ClientAddress}, which resolves the client IP behind the per-IP auth throttle and the security logging. Cloudflare's
 * {@code CF-Connecting-IP} is believed only when the deployment declares itself to be behind Cloudflare, since anywhere else a client could name its
 * own throttle key; whatever the source, the resolved value must look like an address, so it can neither overflow the lockout table's column nor
 * forge a log line. The forwarded-header summary separately reduces hostile header values to printable ASCII.
 */
class ClientAddressTest {

    private static final String CLOUDFLARE_IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final String REMOTE_IP = "10.0.0.1"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final String FORWARDED_FOR_IP = "198.51.100.9"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final String IPV6_IP = "2001:db8::1"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final String UNKNOWN = "unknown";
    private static final boolean TRUSTED = true;
    private static final boolean UNTRUSTED = false;

    @Test
    void resolve_whenTrusted_prefersCloudflareHeaderOverRemoteAddress() {
        assertThat(ClientAddress.resolve(CLOUDFLARE_IP, REMOTE_IP, TRUSTED))
            .as("behind Cloudflare the header should win over the socket remote address")
            .isEqualTo(CLOUDFLARE_IP);
    }

    @Test
    void resolve_whenNotTrusted_ignoresCloudflareHeaderEntirely() {
        assertThat(ClientAddress.resolve(CLOUDFLARE_IP, REMOTE_IP, UNTRUSTED))
            .as("a deployment not behind Cloudflare must key on the connection, not on a header any client can send")
            .isEqualTo(REMOTE_IP);
    }

    @Test
    void resolve_whenNotTrusted_doesNotFallBackToTheCloudflareHeader() {
        assertThat(ClientAddress.resolve(CLOUDFLARE_IP, null, UNTRUSTED))
            .as("an untrusted header must not be used even when there is no remote address to fall back to")
            .isEqualTo(UNKNOWN);
    }

    @Test
    void resolve_whenTrusted_stripsWhitespaceFromCloudflareHeader() {
        assertThat(ClientAddress.resolve("  " + CLOUDFLARE_IP + "  ", null, TRUSTED))
            .as("a padded Cloudflare header should be trimmed to the bare address")
            .isEqualTo(CLOUDFLARE_IP);
    }

    @Test
    void resolve_fallsBackToRemoteAddressWhenCloudflareHeaderAbsent() {
        assertThat(ClientAddress.resolve(null, REMOTE_IP, TRUSTED))
            .as("with no Cloudflare header the socket remote address should be used")
            .isEqualTo(REMOTE_IP);
    }

    @Test
    void resolve_fallsBackToRemoteAddressWhenCloudflareHeaderBlank() {
        assertThat(ClientAddress.resolve("   ", REMOTE_IP, TRUSTED))
            .as("a blank Cloudflare header should be ignored in favour of the remote address")
            .isEqualTo(REMOTE_IP);
    }

    @Test
    void resolve_returnsUnknownWhenNeitherSourceIsPresent() {
        assertThat(ClientAddress.resolve(null, null, TRUSTED))
            .as("with neither source the resolver should report the address as unknown")
            .isEqualTo(UNKNOWN);
    }

    @Test
    void resolve_returnsUnknownWhenRemoteAddressBlankAndNoCloudflareHeader() {
        assertThat(ClientAddress.resolve(null, "  ", TRUSTED))
            .as("a blank remote address with no Cloudflare header should be unknown")
            .isEqualTo(UNKNOWN);
    }

    @Test
    void resolve_acceptsAnIpv6Literal() {
        assertThat(ClientAddress.resolve(IPV6_IP, null, TRUSTED))
            .as("an IPv6 literal is an address and should be accepted")
            .isEqualTo(IPV6_IP);
    }

    @Test
    void resolve_acceptsIpv6LiteralWithZoneIdentifier() {
        assertThat(ClientAddress.resolve(null, "fe80::1%eth0", TRUSTED))
            .as("a link-local remote address carries a zone identifier and should still be accepted")
            .isEqualTo("fe80::1%eth0");
    }

    @Test
    void resolve_rejectsTrustedHeaderCarryingNewline() {
        assertThat(ClientAddress.resolve(CLOUDFLARE_IP + "\nforged log line", REMOTE_IP, TRUSTED))
            .as("a value carrying a newline could forge a log line and must be discarded, not passed on")
            .isEqualTo(REMOTE_IP);
    }

    @Test
    void resolve_rejectsTrustedHeaderCarryingNonAsciiText() {
        assertThat(ClientAddress.resolve("caf" + (char) 0xE9, REMOTE_IP, TRUSTED))
            .as("a non-ASCII value renders as '?' on the production console and must be discarded")
            .isEqualTo(REMOTE_IP);
    }

    @Test
    void resolve_rejectsTrustedHeaderLongerThanTheAddressBound() {
        assertThat(ClientAddress.resolve("1".repeat(46), REMOTE_IP, TRUSTED))
            .as("a value longer than the longest possible IPv6 literal must be discarded, so it cannot overflow the lockout column")
            .isEqualTo(REMOTE_IP);
    }

    @Test
    void resolve_acceptsTrustedHeaderAtTheAddressBound() {
        final String longestLiteral = "1".repeat(45);

        assertThat(ClientAddress.resolve(longestLiteral, REMOTE_IP, TRUSTED))
            .as("a value exactly at the bound is still address-shaped and should be accepted")
            .isEqualTo(longestLiteral);
    }

    @Test
    void resolve_rejectsZoneIdentifierLongerThanTheBound() {
        assertThat(ClientAddress.resolve("fe80::1%" + "e".repeat(17), REMOTE_IP, TRUSTED))
            .as("an over-long zone identifier must be discarded like any other unbounded value")
            .isEqualTo(REMOTE_IP);
    }

    @Test
    void resolve_returnsUnknownWhenTheRemoteAddressIsNotAddressShaped() {
        assertThat(ClientAddress.resolve(null, "not-an-address", TRUSTED))
            .as("the shape check applies to the remote address too, rather than only to the header")
            .isEqualTo(UNKNOWN);
    }

    @Test
    void isHeaderIgnored_whenPresentAndNotTrusted() {
        assertThat(ClientAddress.isHeaderIgnored(CLOUDFLARE_IP, UNTRUSTED))
            .as("a header that arrived but is not believed is the configuration worth warning about")
            .isTrue();
    }

    @Test
    void isHeaderIgnored_whenPresentAndTrusted() {
        assertThat(ClientAddress.isHeaderIgnored(CLOUDFLARE_IP, TRUSTED))
            .as("a trusted header is being used, so there is nothing to warn about")
            .isFalse();
    }

    @Test
    void isHeaderIgnored_whenAbsent() {
        assertThat(ClientAddress.isHeaderIgnored(null, UNTRUSTED))
            .as("no header means nothing upstream set one, so the flag being off is simply correct")
            .isFalse();
    }

    @Test
    void isHeaderIgnored_whenBlank() {
        assertThat(ClientAddress.isHeaderIgnored("   ", UNTRUSTED))
            .as("a blank header says nothing about the deployment and must not raise the warning")
            .isFalse();
    }

    @Test
    void isOriginUnprotected_whenTrustedAndHeaderAbsent() {
        assertThat(ClientAddress.isOriginUnprotected(null, TRUSTED))
            .as("believing the header while a request arrives without one means the origin is reachable outside Cloudflare")
            .isTrue();
    }

    @Test
    void isOriginUnprotected_whenTrustedAndHeaderBlank() {
        assertThat(ClientAddress.isOriginUnprotected("   ", TRUSTED))
            .as("a blank header is no header, so it shows the same unprotected origin")
            .isTrue();
    }

    @Test
    void isOriginUnprotected_whenTrustedAndHeaderPresent() {
        assertThat(ClientAddress.isOriginUnprotected(CLOUDFLARE_IP, TRUSTED))
            .as("a request carrying the header came through Cloudflare, which is the configuration working as intended")
            .isFalse();
    }

    @Test
    void isOriginUnprotected_whenNotTrusted() {
        assertThat(ClientAddress.isOriginUnprotected(null, UNTRUSTED))
            .as("a deployment that never believes the header cannot be exposed by it, however the request arrived")
            .isFalse();
    }

    @Test
    void headerWarnings_areMutuallyExclusive() {
        final List<Boolean> bothFired = Stream.of(CLOUDFLARE_IP, null, "  ")
            .flatMap(header -> Stream.of(bothFire(header, TRUSTED), bothFire(header, UNTRUSTED)))
            .toList();

        assertThat(bothFired)
            .as("the two warnings need opposite values of one flag, which is what lets a single latch serve both")
            .containsOnly(false);
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

    private static boolean bothFire(final @Nullable String ip, final boolean trusted) {
        return ClientAddress.isHeaderIgnored(ip, trusted) && ClientAddress.isOriginUnprotected(ip, trusted);
    }
}
