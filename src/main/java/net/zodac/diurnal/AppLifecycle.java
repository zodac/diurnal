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
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import net.zodac.diurnal.auth.OidcDiscovery;
import net.zodac.diurnal.config.OidcConfig;
import net.zodac.diurnal.config.PasswordAuthConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

/**
 * Validates the authentication configuration at startup and logs the resolved auth setup.
 */
@ApplicationScoped
public class AppLifecycle {

    private static final Logger LOGGER = LogManager.getLogger(AppLifecycle.class);

    // Bounded probe of the IdP discovery endpoint: a few short-timeout attempts so a genuine misconfiguration fails the boot within seconds, while a
    // brief provider blip during a restart is tolerated by the retry.
    private static final int PROBE_ATTEMPTS = 3;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2L);
    private static final Duration PROBE_RETRY_BACKOFF = Duration.ofSeconds(1L);

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url", defaultValue = "")
    String oidcIssuerUrl = "";

    @ConfigProperty(name = "quarkus.oidc.discovery-enabled", defaultValue = "true")
    boolean oidcDiscoveryEnabled;

    @Inject
    OidcConfig oidcConfig;

    /**
     * Fails fast if no auth method is enabled, or if OIDC is on without an issuer URL.
     */
    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes StartupEvent ev) {
        validateAuthConfig();
        verifyOidcDiscovery();

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

    /**
     * When enabled, probes the IdP's discovery endpoint at startup and fails fast on an unreachable or invalid provider. Quarkus fetches the
     * discovery document lazily (on the first login), so without this a misconfigured issuer boots cleanly and is only discovered by the first user
     * bounced to {@code /login?error=oidc}. The probe is skipped unless OIDC is enabled, {@code OIDC_VERIFY_ON_STARTUP} is on, and Quarkus discovery
     * is enabled (with manual endpoints there is no discovery document to fetch). The {@link OidcDiscovery} policy owns the branching; this method is
     * the untestable HTTP glue.
     *
     * @throws IllegalStateException if the provider cannot be reached or does not serve a valid discovery document
     */
    void verifyOidcDiscovery() {
        if (!OidcDiscovery.shouldVerify(oidcEnabled, oidcConfig.verifyOnStartup(), oidcDiscoveryEnabled)) {
            return;
        }

        final String discoveryUrl = OidcDiscovery.discoveryUrl(oidcIssuerUrl);
        LOGGER.debug("Verifying OIDC issuer at '{}'", discoveryUrl);

        final HttpResponse<String> response = fetchDiscovery(discoveryUrl);
        final Optional<String> failure;
        if (response == null) {
            LOGGER.debug("OIDC discovery endpoint {} could not be reached after {} attempt(s)", discoveryUrl, PROBE_ATTEMPTS);
            failure = OidcDiscovery.validationFailure(oidcIssuerUrl, false, 0, "");
        } else {
            LOGGER.debug("OIDC discovery endpoint {} responded with HTTP {}, body: {}", discoveryUrl, response.statusCode(), response.body());
            failure = OidcDiscovery.validationFailure(oidcIssuerUrl, true, response.statusCode(), response.body());
        }

        failure.ifPresent(message -> {
            throw new IllegalStateException(message);
        });
        LOGGER.debug("OIDC discovery endpoint {} verified successfully", discoveryUrl);
    }

    /**
     * Fetches the discovery document over HTTP with a bounded timeout, retrying only a connection/timeout failure (an answered request, even a wrong
     * status, is classified as-is). Returns {@code null} when the provider could not be reached after every attempt, or the URL is malformed.
     *
     * @param discoveryUrl the discovery endpoint URL
     * @return the HTTP response, or {@code null} when the provider was unreachable
     */
    @Nullable
    private static HttpResponse<String> fetchDiscovery(final String discoveryUrl) {
        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                .uri(URI.create(discoveryUrl))
                .timeout(PROBE_TIMEOUT)
                .GET()
                .build();
        } catch (final IllegalArgumentException e) {
            // A malformed issuer URL is itself a misconfiguration - report it as unreachable so the boot fails fast.
            LOGGER.debug("OIDC discovery URL {} is malformed: {}", discoveryUrl, e.getMessage());
            return null;
        }

        try (HttpClient client = HttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()) {
            for (int attempt = 1; attempt <= PROBE_ATTEMPTS; attempt++) {
                try {
                    return client.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (final IOException e) {
                    LOGGER.debug("OIDC discovery probe attempt {} of {} failed: {}", attempt, PROBE_ATTEMPTS, e.getMessage());
                    sleepBeforeRetry(attempt);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Sleeps the retry back-off between probe attempts, unless this was the final attempt. Restores the interrupt flag if interrupted while waiting.
     *
     * @param attempt the attempt that just failed (1-based)
     */
    private static void sleepBeforeRetry(final int attempt) {
        if (attempt >= PROBE_ATTEMPTS) {
            return;
        }
        try {
            Thread.sleep(PROBE_RETRY_BACKOFF.toMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
