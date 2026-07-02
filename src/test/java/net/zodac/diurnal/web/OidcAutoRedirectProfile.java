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

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile that enables OIDC with {@code oidc.auto.redirect=true} to exercise the
 * {@code /login} → {@code /oidc-login} auto-redirect branch in {@link WebResource}.
 *
 * <p>OIDC discovery is disabled and every endpoint path is pinned to a local placeholder so the
 * tenant is created without any network call to a real identity provider — the auto-redirect
 * decision is taken purely from config ({@code quarkus.oidc.tenant-enabled} + {@code oidc.auto.redirect}),
 * so no live IdP is needed and {@code /login} never actually initiates the code flow.
 */
public final class OidcAutoRedirectProfile implements QuarkusTestProfile {

    /**
     * Turns OIDC on (with a placeholder issuer and manual endpoints, discovery off) and enables the
     * auto-redirect behaviour.
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
                "oidc.auto.redirect", "true");
    }
}
