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

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Validates the authentication configuration at startup and logs the resolved auth setup.
 */
@ApplicationScoped
public class AppLifecycle {

    private static final Logger LOGGER = LogManager.getLogger(AppLifecycle.class);

    @ConfigProperty(name = "password.auth.enabled", defaultValue = "true")
    boolean passwordAuthEnabled;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url", defaultValue = "")
    String oidcIssuerUrl = "";

    @ConfigProperty(name = "oidc.provider.name", defaultValue = "your identity provider")
    String oidcProviderName = "your identity provider";

    @ConfigProperty(name = "oidc.auto.redirect", defaultValue = "false")
    boolean oidcAutoRedirect;

    /**
     * Fails fast if no auth method is enabled, or if OIDC is on without an issuer URL.
     */
    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes StartupEvent ev) {
        if (!passwordAuthEnabled && !oidcEnabled) {
            throw new IllegalStateException(
                "Both PASSWORD_AUTH_ENABLED and OIDC_ENABLED are false — "
                + "at least one authentication method must be enabled.");
        }

        if (oidcEnabled && oidcIssuerUrl.isBlank()) {
            throw new IllegalStateException(
                "OIDC_ENABLED=true but OIDC_ISSUER_URL is not set.");
        }

        LOGGER.info("=================================================");
        LOGGER.info("  Diurnal started");
        LOGGER.info("  Password auth : {}", passwordAuthEnabled ? "enabled" : "disabled");
        if (oidcEnabled) {
            LOGGER.info("  OIDC          : enabled  (issuer: {}, provider: {}, auto-redirect: {})",
                    oidcIssuerUrl, oidcProviderName, oidcAutoRedirect);
        } else {
            LOGGER.info("  OIDC          : disabled");
            if (oidcAutoRedirect) {
                LOGGER.warn("  OIDC_AUTO_REDIRECT=true has no effect because OIDC_ENABLED=false");
            }
        }
        LOGGER.info("=================================================");
    }
}
