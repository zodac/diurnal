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

package net.zodac.diurnal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CsrfProtectionFilterTest {

    private static final String HOST = "diurnal.example.com";
    private static final String ORIGIN = "https://diurnal.example.com";

    // ── Safe methods are never a violation ────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "TRACE"})
    void isCsrfViolation_safeMethod_neverViolation(final String method) {
        // Even a cross-site origin on a safe method is allowed (safe methods do not change state).
        assertThat(CsrfProtectionFilter.isCsrfViolation(method, true, "https://evil.example", null, HOST))
            .as("safe method must never be treated as a CSRF violation")
            .isFalse();
    }

    // ── Non-cookie requests are out of scope ──────────────────────────────────

    @Test
    void isCsrfViolation_noSessionCookie_notGuarded() {
        // A Bearer/Basic API call (no ambient cookie) is not a CSRF vector, even cross-site.
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", false, "https://evil.example", null, HOST))
            .as("a request without a session cookie must not be guarded")
            .isFalse();
    }

    // ── Origin present: must match ────────────────────────────────────────────

    @Test
    void isCsrfViolation_matchingOrigin_allowed() {
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, ORIGIN, null, HOST))
            .as("a same-site Origin must be allowed")
            .isFalse();
    }

    @Test
    void isCsrfViolation_matchingOriginDifferentCase_allowed() {
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, "https://DIURNAL.EXAMPLE.COM", null, HOST))
            .as("host comparison must be case-insensitive")
            .isFalse();
    }

    @Test
    void isCsrfViolation_matchingOriginWithPort_allowed() {
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, "http://127.0.0.1:8080", null, "127.0.0.1:8080"))
            .as("a same-site Origin including a port must be allowed")
            .isFalse();
    }

    @Test
    void isCsrfViolation_crossSiteOrigin_rejected() {
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, "https://evil.example", null, HOST))
            .as("a cross-site Origin must be rejected")
            .isTrue();
    }

    @Test
    void isCsrfViolation_sameHostDifferentPort_rejected() {
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, "http://127.0.0.1:9999", null, "127.0.0.1:8080"))
            .as("a different port is a different origin and must be rejected")
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "PATCH", "DELETE"})
    void isCsrfViolation_crossSiteOriginOtherUnsafeMethods_rejected(final String method) {
        assertThat(CsrfProtectionFilter.isCsrfViolation(method, true, "https://evil.example", null, HOST))
            .as("every unsafe method must be guarded")
            .isTrue();
    }

    @Test
    void isCsrfViolation_opaqueNullOrigin_rejected() {
        // A sandboxed iframe sends the literal Origin: null — it must not bypass the check.
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, "null", null, HOST))
            .as("an opaque \"null\" Origin must be rejected")
            .isTrue();
    }

    @Test
    void isCsrfViolation_relativeOrigin_rejected() {
        // A value with no scheme cannot be parsed to an authority: reject rather than trust it.
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, "diurnal.example.com", null, HOST))
            .as("an unparseable Origin must be rejected")
            .isTrue();
    }

    @Test
    void isCsrfViolation_originPresentButNoExpectedAuthority_rejected() {
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, ORIGIN, null, null))
            .as("with no addressable host to compare against, a cookie POST must be rejected")
            .isTrue();
    }

    @Test
    void isCsrfViolation_originPresentButBlankExpectedAuthority_rejected() {
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, ORIGIN, null, "   "))
            .as("a blank expected authority must not be treated as a match")
            .isTrue();
    }

    // ── Origin absent: fall back to Referer ───────────────────────────────────

    @Test
    void isCsrfViolation_matchingReferer_allowed() {
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, null, "https://diurnal.example.com/settings", HOST))
            .as("a same-site Referer must be allowed when no Origin is present")
            .isFalse();
    }

    @Test
    void isCsrfViolation_crossSiteReferer_rejected() {
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, null, "https://evil.example/attack", HOST))
            .as("a cross-site Referer must be rejected")
            .isTrue();
    }

    @Test
    void isCsrfViolation_originPreferredOverReferer_whenOriginMismatches() {
        // Origin is authoritative: a good Referer must not rescue a bad Origin.
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, "https://evil.example", "https://diurnal.example.com/x", HOST))
            .as("Origin must take precedence over Referer")
            .isTrue();
    }

    // ── Neither header present: non-browser client, allowed ───────────────────

    @Test
    void isCsrfViolation_noOriginNoReferer_allowed() {
        // curl / an API test harness sends neither header and is not driving a victim's cookie.
        assertThat(CsrfProtectionFilter.isCsrfViolation("POST", true, null, null, HOST))
            .as("a cookie POST with neither Origin nor Referer must be allowed")
            .isFalse();
    }

    // ── expectedAuthority resolution ──────────────────────────────────────────

    @Test
    void expectedAuthority_usesHostWhenNoForwardedHost() {
        assertThat(CsrfProtectionFilter.expectedAuthority(null, HOST))
            .as("unexpected value")
            .isEqualTo(HOST);
    }

    @Test
    void expectedAuthority_prefersForwardedHostOverHost() {
        assertThat(CsrfProtectionFilter.expectedAuthority("public.example.com", "internal:8080"))
            .as("X-Forwarded-Host must take precedence over Host")
            .isEqualTo("public.example.com");
    }

    @Test
    void expectedAuthority_blankForwardedHostFallsBackToHost() {
        assertThat(CsrfProtectionFilter.expectedAuthority("   ", HOST))
            .as("a blank X-Forwarded-Host must fall back to Host")
            .isEqualTo(HOST);
    }

    @Test
    void expectedAuthority_multiProxyList_usesFirstEntry() {
        assertThat(CsrfProtectionFilter.expectedAuthority("public.example.com, internal.example.com", "internal:8080"))
            .as("the first entry of a comma-separated forwarded-host list is the client-facing host")
            .isEqualTo("public.example.com");
    }

    @Test
    void expectedAuthority_multiProxyListWithSpacing_trimmed() {
        assertThat(CsrfProtectionFilter.expectedAuthority("  public.example.com  , internal.example.com", null))
            .as("the resolved authority must be trimmed of surrounding whitespace")
            .isEqualTo("public.example.com");
    }

    @Test
    void expectedAuthority_bothNull_returnsNull() {
        assertThat(CsrfProtectionFilter.expectedAuthority(null, null))
            .as("with neither header present there is no addressable host")
            .isNull();
    }

    @Test
    void expectedAuthority_bothBlank_returnsNull() {
        assertThat(CsrfProtectionFilter.expectedAuthority("", "   "))
            .as("blank headers must resolve to no addressable host")
            .isNull();
    }

    @Test
    void expectedAuthority_forwardedHostLeadingCommaEmptyFirstEntry_returnsNull() {
        assertThat(CsrfProtectionFilter.expectedAuthority(",public.example.com", null))
            .as("an empty first list entry must resolve to no addressable host")
            .isNull();
    }

    // ── authorityOf parsing ───────────────────────────────────────────────────

    @Test
    void authorityOf_originWithoutPath_returnsHost() {
        assertThat(CsrfProtectionFilter.authorityOf("https://host.example.com"))
            .as("unexpected value")
            .isEqualTo("host.example.com");
    }

    @Test
    void authorityOf_originWithHostAndPort_returnsHostAndPort() {
        assertThat(CsrfProtectionFilter.authorityOf("http://127.0.0.1:8080"))
            .as("unexpected value")
            .isEqualTo("127.0.0.1:8080");
    }

    @Test
    void authorityOf_refererWithPath_returnsAuthorityOnly() {
        assertThat(CsrfProtectionFilter.authorityOf("https://host.example.com:8443/settings?tab=1"))
            .as("the path and query must be stripped, leaving only host:port")
            .isEqualTo("host.example.com:8443");
    }

    @Test
    void authorityOf_noScheme_returnsNull() {
        assertThat(CsrfProtectionFilter.authorityOf("host.example.com/settings"))
            .as("a value without a scheme separator cannot be parsed to an authority")
            .isNull();
    }

    @Test
    void authorityOf_opaqueNullLiteral_returnsNull() {
        assertThat(CsrfProtectionFilter.authorityOf("null"))
            .as("the opaque \"null\" origin has no authority")
            .isNull();
    }

    @Test
    void authorityOf_schemeSeparatorAtStart_stillParsesHost() {
        // Pins the schemeEnd < 0 boundary: an index of exactly 0 must not be treated as "no scheme".
        assertThat(CsrfProtectionFilter.authorityOf("://host.example.com"))
            .as("a scheme separator at index 0 must still yield the host")
            .isEqualTo("host.example.com");
    }

    @Test
    void authorityOf_emptyAuthorityBeforePath_returnsNull() {
        // Pins the pathStart < 0 boundary and the blank-authority guard: "https:///x" has an empty
        // authority (a '/' immediately after the scheme), which must resolve to null, not "/x".
        assertThat(CsrfProtectionFilter.authorityOf("https:///x"))
            .as("an empty authority (path immediately after scheme) must resolve to null")
            .isNull();
    }

    @Test
    void authorityOf_schemeButEmptyRemainder_returnsNull() {
        assertThat(CsrfProtectionFilter.authorityOf("https://"))
            .as("a scheme with no authority must resolve to null")
            .isNull();
    }
}
