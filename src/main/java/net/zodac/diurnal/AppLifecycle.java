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
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.zodac.diurnal.auth.OidcDiscovery;
import net.zodac.diurnal.config.NotesEncryptionConfig;
import net.zodac.diurnal.config.OidcConfig;
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.config.QuarkusOidcConfig;
import net.zodac.diurnal.crypto.MasterKey;
import net.zodac.diurnal.note.KeyReconciliation;
import net.zodac.diurnal.note.NoteKeys;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private final PasswordAuthConfig passwordAuthConfig;
    private final QuarkusOidcConfig quarkusOidcConfig;
    private final OidcConfig oidcConfig;
    private final NotesEncryptionConfig notesEncryptionConfig;
    private final NoteKeys noteKeys;

    /**
     * Injects the authentication configuration views validated and logged at startup.
     *
     * @param passwordAuthConfig the password-auth settings
     * @param quarkusOidcConfig the Quarkus OIDC tenant settings
     * @param oidcConfig the application OIDC policy settings
     * @param notesEncryptionConfig the notes encryption settings, whose key is validated at startup
     * @param noteKeys the notes key service, used to prove the configured key opens the stored data
     */
    @Inject
    public AppLifecycle(final PasswordAuthConfig passwordAuthConfig, final QuarkusOidcConfig quarkusOidcConfig, final OidcConfig oidcConfig,
        final NotesEncryptionConfig notesEncryptionConfig, final NoteKeys noteKeys) {
        this.passwordAuthConfig = passwordAuthConfig;
        this.quarkusOidcConfig = quarkusOidcConfig;
        this.oidcConfig = oidcConfig;
        this.notesEncryptionConfig = notesEncryptionConfig;
        this.noteKeys = noteKeys;
    }

    /**
     * Fails fast if no auth method is enabled, or if OIDC is on without an issuer URL.
     */
    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes final StartupEvent ev) {
        validateAuthConfig();
        validateNotesEncryptionKey();
        verifyNotesEncryptionKeyOpensExistingData();
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
        if (quarkusOidcConfig.tenantEnabled()) {
            LOGGER.info("  OIDC          : enabled  (issuer: {}, provider: {}, auto-redirect: {})",
                quarkusOidcConfig.authServerUrl(), oidcConfig.providerName(), oidcConfig.autoRedirect());
        } else {
            LOGGER.info("  OIDC          : disabled");
            if (oidcConfig.autoRedirect()) {
                LOGGER.warn("  OIDC_AUTO_REDIRECT=true has no effect because OIDC_ENABLED=false");
            }
        }
        LOGGER.info("=================================================");
    }

    /**
     * Fails fast when the notes encryption key is missing or malformed.
     *
     * <p>
     * Checked here rather than on first use because the failure is a deployment mistake, not a runtime case: without a usable key no note can be read
     * or written, and finding that out when a user first opens their journal is far worse than refusing to boot. The message names the variable and
     * how to generate one, and never the value.
     *
     * @throws IllegalStateException if the configured key is absent, not base64, or the wrong length
     */
    void validateNotesEncryptionKey() {
        MasterKey.validate(notesEncryptionConfig.key().orElse(null)).ifPresent(problem -> {
            throw new IllegalStateException(problem);
        });

        // A typo in a retired key would otherwise look exactly like "no previous key was configured", and the boot would
        // fail with a misleading reason - a mismatch rather than the malformed value that actually caused it.
        for (final String retired : notesEncryptionConfig.previousKeys().orElseGet(List::of)) {
            if (retired.isBlank()) {
                continue;
            }
            MasterKey.validate(retired).ifPresent(problem -> {
                throw new IllegalStateException("NOTE_ENCRYPTION_PREVIOUS_KEYS holds an unusable entry: " + problem);
            });
        }
    }

    /**
     * Fails fast when the configured notes key is well-formed but does not open the data this installation already holds.
     *
     * <p>
     * This is the check that matters. {@link #validateNotesEncryptionKey()} proves only that {@code NOTE_ENCRYPTION_KEY} is 32 bytes of base64 — a
     * rotated, regenerated or mistyped key passes that and then opens nothing, at which point every note disappears from every screen while the rows
     * sit untouched in the table. The calendar's day markers would still show, because they are computed from dates and never read content, so a user
     * would see markers saying they wrote something beside an empty box. Refusing to start is the only honest answer to that.
     *
     * <p>
     * Best-effort about finding something to check: a fresh installation with no accounts has nothing to prove and passes, as does one whose schema
     * has not been migrated yet (the query throws and is swallowed). Only a definite mismatch fails.
     *
     * @throws IllegalStateException if stored keys exist and the configured key opens none of them
     */
    @Transactional
    void verifyNotesEncryptionKeyOpensExistingData() {
        final KeyReconciliation outcome;
        try {
            outcome = noteKeys.reconcile();
        } catch (final PersistenceException e) {
            // No schema to read yet - a first boot, where there is by definition nothing to verify.
            LOGGER.debug("Skipping the notes encryption key check: the schema is not readable yet", e);
            return;
        }

        if (outcome.unopenable() > 0) {
            throw new IllegalStateException(
                "NOTE_ENCRYPTION_KEY does not open the notes data already in this database, for at least " + outcome.unopenable()
                + " account(s). It is well-formed but not the key that data was written under - starting would make those notes unreadable while "
                + "appearing to work. Restore the original key, put it in NOTE_ENCRYPTION_PREVIOUS_KEYS to rotate onto the new one, or clear the "
                + "user_notes_keys and notes tables to start afresh (which discards every note).");
        }

        if (outcome.rotated() > 0) {
            LOGGER.info("Notes encryption key rotated: {} account(s) moved onto the current NOTE_ENCRYPTION_KEY. "
                + "NOTE_ENCRYPTION_PREVIOUS_KEYS can be removed once every deployment has started at least once.", outcome.rotated());
        }
    }

    /**
     * Fails fast when the authentication configuration is invalid: no auth method enabled, or OIDC enabled without an issuer URL. Extracted from
     * {@link #onStart(StartupEvent)} so the guards can be exercised directly without booting the application (the "no auth method" case throws before
     * startup can complete).
     *
     * @throws IllegalStateException if neither password auth nor OIDC is enabled, or if OIDC is enabled but no issuer URL is configured
     */
    void validateAuthConfig() {
        if (!passwordAuthConfig.enabled() && !quarkusOidcConfig.tenantEnabled()) {
            throw new IllegalStateException(
                "Both PASSWORD_AUTH_ENABLED and OIDC_ENABLED are false - "
                + "at least one authentication method must be enabled.");
        }

        if (quarkusOidcConfig.tenantEnabled() && quarkusOidcConfig.authServerUrl().isBlank()) {
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
        if (!OidcDiscovery.shouldVerify(quarkusOidcConfig.tenantEnabled(), oidcConfig.verifyOnStartup(), quarkusOidcConfig.discoveryEnabled())) {
            return;
        }

        final String issuerUrl = quarkusOidcConfig.authServerUrl();
        final String discoveryUrl = OidcDiscovery.discoveryUrl(issuerUrl);
        LOGGER.debug("Verifying OIDC issuer at '{}'", discoveryUrl);

        final HttpResponse<String> response = fetchDiscovery(discoveryUrl);
        final Optional<String> failure;
        if (response == null) {
            LOGGER.debug("OIDC discovery endpoint {} could not be reached after {} attempt(s)", discoveryUrl, PROBE_ATTEMPTS);
            failure = OidcDiscovery.validationFailure(issuerUrl, false, 0, "");
        } else {
            LOGGER.trace("OIDC discovery endpoint {} responded with HTTP {}, body: {}", discoveryUrl, response.statusCode(), response.body());
            failure = OidcDiscovery.validationFailure(issuerUrl, true, response.statusCode(), response.body());
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
