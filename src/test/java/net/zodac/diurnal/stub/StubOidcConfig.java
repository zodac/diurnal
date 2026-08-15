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

package net.zodac.diurnal.stub;

import java.util.Optional;
import net.zodac.diurnal.auth.oidc.OidcConfig;

/**
 * Reusable {@link OidcConfig} stub built from its record components. Use {@link #inert()} for a code path that never reads the OIDC settings.
 *
 * @param providerName the human-readable identity-provider name
 * @param autoRedirect whether the login page should auto-redirect to the IdP
 * @param verifyOnStartup whether the discovery endpoint is probed at startup
 * @param logoutUrl the optional RP-initiated logout URL
 * @param adminGroup the optional IdP group mapped to the administrator role
 * @param userGroup the optional IdP group required for any access
 */
public record StubOidcConfig(String providerName, boolean autoRedirect, boolean verifyOnStartup, Optional<String> logoutUrl,
    Optional<String> adminGroup, Optional<String> userGroup) implements OidcConfig {

    /**
     * An inert stub: a placeholder provider name, both flags off, and no group or logout overrides.
     *
     * @return an inert {@link StubOidcConfig}
     */
    public static StubOidcConfig inert() {
        return new StubOidcConfig("stub", false, false, Optional.empty(), Optional.empty(), Optional.empty());
    }
}
