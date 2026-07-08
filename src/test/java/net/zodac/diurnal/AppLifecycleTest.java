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

package net.zodac.diurnal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link AppLifecycle#validateAuthConfig()} startup validation of the authentication configuration.
 *
 * <p>These exercise the fail-fast guards directly (constructing the bean and calling the validation
 * method) rather than as a {@link io.quarkus.test.junit.QuarkusTest}, because the "no auth method
 * enabled" case throws before the application can finish booting. There is no running app to make
 * an HTTP call against.
 */
class AppLifecycleTest {

    // ── Both auth mechanisms disabled → refuse to start ──────────────────────────────────────────

    @Test
    void validate_passwordAndOidcBothDisabled_throws() {
        final AppLifecycle lifecycle = lifecycle(false, false, "");

        assertThatThrownBy(lifecycle::validateAuthConfig)
            .as("startup must fail fast when neither password auth nor OIDC is enabled")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least one authentication method must be enabled");
    }

    // ── OIDC enabled without an issuer URL → refuse to start ─────────────────────────────────────

    @Test
    void validate_oidcEnabledWithBlankIssuer_throws() {
        final AppLifecycle lifecycle = lifecycle(false, true, "   ");

        assertThatThrownBy(lifecycle::validateAuthConfig)
            .as("OIDC cannot be enabled without an issuer URL")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OIDC_ISSUER_URL is not set");
    }

    // ── Valid configurations → no exception ──────────────────────────────────────────────────────

    @Test
    void validate_passwordOnly_doesNotThrow() {
        final AppLifecycle lifecycle = lifecycle(true, false, "");

        assertThatCode(lifecycle::validateAuthConfig)
            .as("password-only auth (OIDC disabled) is a valid configuration")
            .doesNotThrowAnyException();
    }

    @Test
    void validate_oidcOnlyWithIssuer_doesNotThrow() {
        final AppLifecycle lifecycle = lifecycle(false, true, "https://diurnal.example.com");

        assertThatCode(lifecycle::validateAuthConfig)
            .as("OIDC-only auth with a configured issuer is a valid configuration")
            .doesNotThrowAnyException();
    }

    @Test
    void validate_bothEnabled_doesNotThrow() {
        final AppLifecycle lifecycle = lifecycle(true, true, "https://diurnal.example.com");

        assertThatCode(lifecycle::validateAuthConfig)
            .as("password + OIDC together is a valid configuration")
            .doesNotThrowAnyException();
    }

    private static AppLifecycle lifecycle(final boolean passwordEnabled, final boolean oidcEnabled, final String issuerUrl) {
        final AppLifecycle lifecycle = new AppLifecycle();
        lifecycle.passwordAuthConfig = () -> passwordEnabled;
        lifecycle.oidcEnabled = oidcEnabled;
        lifecycle.oidcIssuerUrl = issuerUrl;
        return lifecycle;
    }
}
