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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import net.zodac.diurnal.config.AppConfig;
import net.zodac.diurnal.config.UpdateCheckConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The production {@link LatestReleaseClient}: queries the configured repository's GitHub REST API ({@code /releases}, newest-first) and takes
 * the most recent published release tag. The list endpoint is used rather than {@code /releases/latest} because the latter excludes pre-releases
 * and 404s for a repository that only publishes them. Pure URL-derivation and tag-extraction live in {@link UpdateCheck}; this bean owns only the
 * bounded, best-effort HTTP call (the untestable I/O, NO_COVERAGE like the startup OIDC probe in {@code AppLifecycle}).
 */
@ApplicationScoped
public class GitHubLatestReleaseClient implements LatestReleaseClient {

    private static final Logger LOGGER = LogManager.getLogger(GitHubLatestReleaseClient.class);
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String USER_AGENT = "diurnal-update-check";

    @Inject
    AppConfig appConfig;

    @Inject
    UpdateCheckConfig updateCheckConfig;

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
        final HttpRequest request = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(updateCheckConfig.timeout())
            .header("Accept", ACCEPT)
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();

        try (HttpClient client = HttpClient.newBuilder()
            .connectTimeout(updateCheckConfig.timeout())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()) {
            final HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != HttpURLConnection.HTTP_OK) {
                LOGGER.debug("GitHub releases API {} returned HTTP {}", uri, response.statusCode());
                return Optional.empty();
            }
            return UpdateCheck.extractLatestTag(response.body());
        } catch (final IOException e) {
            LOGGER.debug("Update check request to {} failed: {}", uri, e.getMessage());
            return Optional.empty();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
