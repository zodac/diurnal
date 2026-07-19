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

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile pinning an OIDC-only configuration: the OIDC tenant enabled (discovery off, endpoints pinned to a local placeholder, so no network
 * call is ever made) and password authentication DISABLED. Used by the email-adoption tests — {@code OidcLoginPolicy} only adopts an unlinked local
 * account by email when password auth is off, so that state must hold deterministically regardless of the environment's own settings.
 */
public final class OidcOnlyAuthProfile implements QuarkusTestProfile {

    /**
     * Turns the OIDC tenant on (placeholder issuer, manual endpoints, discovery off) and password authentication off.
     *
     * @return the config overrides applied for this profile
     */
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.oidc.tenant-enabled", "true",
                "quarkus.oidc.discovery-enabled", "false",
                "quarkus.oidc.auth-server-url", "http://127.0.0.1:8080/realms/diurnal",
                "quarkus.oidc.authorization-path", "/protocol/openid-connect/auth",
                "quarkus.oidc.token-path", "/protocol/openid-connect/token",
                "quarkus.oidc.jwks-path", "/protocol/openid-connect/certs",
                "quarkus.oidc.user-info-path", "/protocol/openid-connect/userinfo",
                "password.auth.enabled", "false");
    }
}
