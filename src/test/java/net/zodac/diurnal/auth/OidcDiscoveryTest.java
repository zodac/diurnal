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

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OidcDiscovery}, the pure decision core of the startup OIDC discovery probe.
 */
class OidcDiscoveryTest {

    // ── discoveryUrl: build the well-known URL, tolerating a trailing slash and surrounding space ──

    @Test
    void discoveryUrl_noTrailingSlash_appendsWellKnown() {
        assertThat(OidcDiscovery.discoveryUrl("https://diurnal.example.com"))
            .as("the well-known suffix is appended to a bare issuer")
            .isEqualTo("https://diurnal.example.com/.well-known/openid-configuration");
    }

    @Test
    void discoveryUrl_trailingSlash_isNotDoubled() {
        assertThat(OidcDiscovery.discoveryUrl("https://diurnal.example.com/"))
            .as("a single trailing slash on the issuer is trimmed so the path is not doubled")
            .isEqualTo("https://diurnal.example.com/.well-known/openid-configuration");
    }

    @Test
    void discoveryUrl_issuerWithPathAndTrailingSlash_preservesPath() {
        assertThat(OidcDiscovery.discoveryUrl("https://diurnal.example.com/realms/diurnal/"))
            .as("an issuer path is preserved and only the trailing slash is trimmed")
            .isEqualTo("https://diurnal.example.com/realms/diurnal/.well-known/openid-configuration");
    }

    @Test
    void discoveryUrl_surroundingWhitespace_isStripped() {
        assertThat(OidcDiscovery.discoveryUrl("  https://diurnal.example.com  "))
            .as("surrounding whitespace on the configured issuer is stripped")
            .isEqualTo("https://diurnal.example.com/.well-known/openid-configuration");
    }

    // ── validationFailure: unreachable ─────────────────────────────────────────────────────────────

    @Test
    void validationFailure_unreachable_reportsUnreachableWithEscapeHatch() {
        final Optional<String> failure = OidcDiscovery.validationFailure("https://diurnal.example.com", false, 0, "");

        assertThat(failure)
            .as("an unreachable provider is a fail-fast condition")
            .isPresent();
        assertThat(failure.orElseThrow())
            .as("the message names the probed URL and the escape hatch")
            .contains("https://diurnal.example.com/.well-known/openid-configuration")
            .contains("could not be reached")
            .contains("OIDC_VERIFY_ON_STARTUP=false");
    }

    // ── validationFailure: wrong status ────────────────────────────────────────────────────────────

    @Test
    void validationFailure_notFound_reportsTheStatus() {
        final Optional<String> failure = OidcDiscovery.validationFailure("https://diurnal.example.com", true, 404, "Not Found");

        assertThat(failure)
            .as("a non-200 discovery response is a fail-fast condition")
            .isPresent();
        assertThat(failure.orElseThrow())
            .as("the message reports the received status against the expected 200")
            .contains("returned HTTP 404");
    }

    @Test
    void validationFailure_status201_reportsTheStatus() {
        final Optional<String> failure = OidcDiscovery.validationFailure("https://diurnal.example.com", true, 201, "{\"issuer\":\"x\"}");

        assertThat(failure)
            .as("only HTTP 200 is treated as success - a 201 fails")
            .isPresent();
        assertThat(failure.orElseThrow())
            .as("the boundary status is reported")
            .contains("returned HTTP 201");
    }

    // ── validationFailure: reachable, 200, but not a discovery document ─────────────────────────────

    @Test
    void validationFailure_htmlBody_reportsInvalidDocument() {
        final Optional<String> failure = OidcDiscovery.validationFailure("https://diurnal.example.com", true, 200, "<html>Login</html>");

        assertThat(failure)
            .as("a 200 that is not a discovery document (e.g. an HTML page) is a fail-fast condition")
            .isPresent();
        assertThat(failure.orElseThrow())
            .as("the message flags an invalid discovery document")
            .contains("did not return a valid OIDC discovery document");
    }

    @Test
    void validationFailure_jsonWithoutIssuer_reportsInvalidDocument() {
        final Optional<String> failure = OidcDiscovery.validationFailure("https://diurnal.example.com", true, 200, "{}");

        assertThat(failure)
            .as("a JSON object that does not name an issuer is not a valid discovery document")
            .isPresent();
        assertThat(failure.orElseThrow())
            .contains("did not return a valid OIDC discovery document");
    }

    @Test
    void validationFailure_issuerTextNotJsonObject_reportsInvalidDocument() {
        final Optional<String> failure = OidcDiscovery.validationFailure("https://diurnal.example.com", true, 200, "issuer only, not JSON");

        assertThat(failure)
            .as("text mentioning issuer but not a JSON object is not a valid discovery document")
            .isPresent();
        assertThat(failure.orElseThrow())
            .contains("did not return a valid OIDC discovery document");
    }

    // ── validationFailure: healthy provider ────────────────────────────────────────────────────────

    @Test
    void validationFailure_validDiscoveryDocument_isEmpty() {
        final String body = "{\"issuer\":\"https://diurnal.example.com\",\"authorization_endpoint\":\"https://diurnal.example.com/auth\"}";
        final Optional<String> failure = OidcDiscovery.validationFailure("https://diurnal.example.com", true, 200, body);

        assertThat(failure)
            .as("a 200 with a JSON discovery document naming an issuer is healthy - no failure")
            .isEmpty();
    }

    @Test
    void validationFailure_validDocumentWithLeadingWhitespace_isEmpty() {
        final Optional<String> failure = OidcDiscovery.validationFailure("https://diurnal.example.com", true, 200, "  {\"issuer\":\"https://x\"}");

        assertThat(failure)
            .as("leading whitespace before the JSON object is tolerated")
            .isEmpty();
    }
}
