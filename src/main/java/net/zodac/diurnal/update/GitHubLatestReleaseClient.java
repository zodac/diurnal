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

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.zodac.diurnal.config.AppConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The production {@link LatestReleaseClient}: queries the configured repository's GitHub REST API ({@code /releases}, newest-first) and takes
 * the most recent published release tag. The list endpoint is used rather than {@code /releases/latest} because the latter excludes pre-releases
 * and 404s for a repository that only publishes them. Pure URL-derivation and tag-extraction live in {@link UpdateCheck}; this bean owns only the
 * bounded, best-effort call over the {@link GitHubReleasesApi} Quarkus REST client (the untestable I/O, NO_COVERAGE like the startup OIDC probe in
 * {@code AppLifecycle}).
 */
@ApplicationScoped
public class GitHubLatestReleaseClient implements LatestReleaseClient {

    private static final Logger LOGGER = LogManager.getLogger(GitHubLatestReleaseClient.class);

    private final AppConfig appConfig;
    private final UpdateCheckConfig updateCheckConfig;

    /**
     * Injects the application config (repository URL) and the update-check settings.
     *
     * @param appConfig the application config supplying the repository URL
     * @param updateCheckConfig the update-check settings
     */
    @Inject
    public GitHubLatestReleaseClient(final AppConfig appConfig, final UpdateCheckConfig updateCheckConfig) {
        this.appConfig = appConfig;
        this.updateCheckConfig = updateCheckConfig;
    }

    @Override
    public Optional<String> latestReleaseVersion() {
        final String repositoryUrl = appConfig.repositoryUrl();
        final Optional<URI> api = UpdateCheck.githubReleasesApi(repositoryUrl);
        if (api.isEmpty()) {
            LOGGER.debug("Repository URL '{}' is not a GitHub repository - skipping update check", repositoryUrl);
            return Optional.empty();
        }
        return fetchLatestTag(api.get());
    }

    private Optional<String> fetchLatestTag(final URI uri) {
        // The releases URL varies per repository, so the client is built with that full URL as its base URI (the interface method carries no path).
        // A short-lived builder per one-shot startup call mirrors the previous per-call HttpClient; the configured timeout bounds both connect and
        // read so a slow or hung provider can never stall the boot.
        final long timeoutMillis = updateCheckConfig.timeout().toMillis();
        try {
            final GitHubReleasesApi client = QuarkusRestClientBuilder.newBuilder()
                .baseUri(uri)
                .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .build(GitHubReleasesApi.class);
            return UpdateCheck.extractLatestTag(client.listReleases());
        } catch (final WebApplicationException | ProcessingException e) {
            // WebApplicationException = a non-success status (e.g. 404/403); ProcessingException = a connection/timeout failure. Both are best-effort
            // no-ops: the footer simply shows no indicator.
            LOGGER.debug("Update check request to {} failed: {}", uri, e.getMessage());
            return Optional.empty();
        }
    }
}
