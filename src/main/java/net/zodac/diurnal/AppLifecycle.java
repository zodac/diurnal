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
import jakarta.inject.Inject;
import java.lang.management.ManagementFactory;
import java.util.Locale;
import net.zodac.diurnal.config.OidcConfig;
import net.zodac.diurnal.config.PasswordAuthConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Validates the authentication configuration at startup and logs the resolved auth setup.
 */
@ApplicationScoped
public class AppLifecycle {

    private static final Logger LOGGER = LogManager.getLogger(AppLifecycle.class);

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url", defaultValue = "")
    String oidcIssuerUrl = "";

    @Inject
    OidcConfig oidcConfig;

    /**
     * Fails fast if no auth method is enabled, or if OIDC is on without an issuer URL.
     */
    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes StartupEvent ev) {
        validateAuthConfig();

        // Wall-clock time from JVM launch to now, read from the RuntimeMXBean whose start timestamp is
        // set by the runtime before any application code runs. This captures the true cold start — JVM
        // launch, classloading and framework init — not just an in-app stopwatch. It does NOT include
        // any time before the JVM process was exec'd (container scheduling, image pull); that is not
        // observable from within the process. Quarkus additionally logs its own "started in X.XXXs"
        // line (the io.quarkus logger), which is measured from Quarkus bootstrap rather than JVM launch.
        final double coldStartSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;
        LOGGER.debug("System cold start: {}s (JVM launch -> ready)", String.format(Locale.ROOT, "%.3f", coldStartSeconds));

        LOGGER.info("=================================================");
        LOGGER.info("  Diurnal started");
        LOGGER.info("  Password auth : {}", passwordAuthConfig.enabled() ? "enabled" : "disabled");
        if (oidcEnabled) {
            LOGGER.info("  OIDC          : enabled  (issuer: {}, provider: {}, auto-redirect: {})",
                oidcIssuerUrl, oidcConfig.providerName(), oidcConfig.autoRedirect());
        } else {
            LOGGER.info("  OIDC          : disabled");
            if (oidcConfig.autoRedirect()) {
                LOGGER.warn("  OIDC_AUTO_REDIRECT=true has no effect because OIDC_ENABLED=false");
            }
        }
        LOGGER.info("=================================================");
    }

    /**
     * Fails fast when the authentication configuration is invalid: no auth method enabled, or OIDC enabled without an issuer URL. Extracted from
     * {@link #onStart(StartupEvent)} so the guards can be exercised directly without booting the application (the "no auth method" case throws before
     * startup can complete).
     *
     * @throws IllegalStateException if neither password auth nor OIDC is enabled, or if OIDC is enabled but no issuer URL is configured
     */
    void validateAuthConfig() {
        if (!passwordAuthConfig.enabled() && !oidcEnabled) {
            throw new IllegalStateException(
                "Both PASSWORD_AUTH_ENABLED and OIDC_ENABLED are false - "
                + "at least one authentication method must be enabled.");
        }

        if (oidcEnabled && oidcIssuerUrl.isBlank()) {
            throw new IllegalStateException(
                "OIDC_ENABLED=true but OIDC_ISSUER_URL is not set.");
        }
    }
}
