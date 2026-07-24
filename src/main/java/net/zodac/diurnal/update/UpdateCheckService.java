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

package net.zodac.diurnal.update;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.zodac.diurnal.config.AppConfig;
import net.zodac.diurnal.config.ReleaseVersion;
import net.zodac.diurnal.config.UpdateCheckConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Performs a single best-effort "is a newer release available" check at application startup, compares the running version against the latest
 * published GitHub release, logs the outcome, and holds the result for the admin pages' footer. The lookup runs exactly once (at startup) and is
 * never refreshed - so the footer verdict may go stale over a long uptime, which is an accepted trade-off for making no repeated outbound calls. When
 * the check is disabled ({@code app.update-check.enabled=false}) no lookup is made and the footer shows no indicator.
 *
 * <p>
 * All version decisions are the pure {@link UpdateCheck}; this bean owns only the one-shot {@link LatestReleaseClient} call, the stored result (an
 * {@link AtomicReference} for safe cross-thread publication from the startup thread to request threads) and the logging.
 */
@ApplicationScoped
public class UpdateCheckService {

    private static final Logger LOGGER = LogManager.getLogger(UpdateCheckService.class);

    @Inject
    UpdateCheckConfig config;

    @Inject
    AppConfig appConfig;

    @Inject
    LatestReleaseClient releaseClient;

    @ConfigProperty(name = "quarkus.application.version", defaultValue = "dev")
    String mavenVersion = "dev";

    private final AtomicReference<String> latestVersion = new AtomicReference<>();

    /**
     * The update status resolved at startup: whether a newer release than the running version was found, the running version is current, or the
     * latest release could not be determined (the check is disabled, failed, or the repository is not a GitHub repository). A pure read of the stored
     * result - no I/O.
     *
     * @return the resolved update status
     */
    public UpdateStatus status() {
        final String currentVersion = ReleaseVersion.resolve(mavenVersion);
        final String latest = latestVersion.get();
        final String latestReleaseUrl = UpdateCheck.latestReleaseUrl(appConfig.repositoryUrl(), latest);
        return UpdateCheck.evaluate(currentVersion, latest, latestReleaseUrl);
    }

    /**
     * Runs the one-shot update check at application startup, unless it is disabled.
     *
     * @param event the fired {@link StartupEvent}
     */
    void onStartup(@Observes final StartupEvent event) {
        if (!config.enabled()) {
            LOGGER.debug("Update check is disabled - skipping startup version check");
            return;
        }
        checkForUpdate();
    }

    /**
     * Performs the single latest-release lookup: stores the fetched version for the footer and logs the outcome (INFO when a newer version is
     * available, DEBUG otherwise or when the latest version could not be determined). Package-private so the integration test can drive it directly
     * with a mocked client.
     */
    void checkForUpdate() {
        final Optional<String> latest = releaseClient.latestReleaseVersion();
        if (latest.isEmpty()) {
            LOGGER.warn("Could not determine the latest release version - no update indicator will be shown");
            return;
        }

        final String latestVersionValue = latest.get();
        latestVersion.set(latestVersionValue);

        final String currentVersion = ReleaseVersion.resolve(mavenVersion);
        if (UpdateCheck.isUpdateAvailable(currentVersion, latestVersionValue)) {
            LOGGER.info("A newer version is available: {} (currently running {}). See {}",
                latestVersionValue, currentVersion, UpdateCheck.latestReleaseUrl(appConfig.repositoryUrl(), latestVersionValue));
        } else {
            LOGGER.debug("Running the latest version ({}) - no update available", currentVersion);
        }
    }
}
