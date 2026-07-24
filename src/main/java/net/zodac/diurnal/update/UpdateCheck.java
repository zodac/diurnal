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

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The pure decision core of the admin-only update check (unit-tested to full mutation strength). It derives the GitHub releases API URL from the
 * configured repository, extracts the latest release tag from the API's JSON response, compares two version strings, and decides whether a cached
 * lookup has gone stale - every branch that decides "is a newer version available" lives here.
 *
 * <p>
 * The {@code UpdateCheckService} / {@code GitHubLatestReleaseClient} glue owns only the HTTP call and the in-memory cache (the untestable I/O and
 * timing); this class is stateless and side-effect free.
 */
public final class UpdateCheck {

    private static final Pattern GITHUB_REPOSITORY = Pattern.compile("(?i)^https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");
    private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERSION_CORE = Pattern.compile("\\d+(?:\\.\\d+)*");
    private static final String GITHUB_API_BASE = "https://api.github.com/repos/";
    private static final String RELEASES_PATH = "/releases";
    private static final String RELEASE_TAG_PATH = "/releases/tag/";

    private UpdateCheck() {

    }

    /**
     * Builds the GitHub REST API "list releases" URL for a repository, or empty when the configured repository is not a recognised GitHub URL (the
     * only host this check supports). The list endpoint is used deliberately in preference to {@code /releases/latest}: that endpoint excludes
     * pre-releases and drafts, so it 404s for a repository whose newest (or only) releases are pre-releases, whereas the list returns every published
     * release newest-first (drafts are still excluded for the anonymous caller this check makes). Tolerates an optional {@code .git} suffix and a
     * single trailing slash.
     *
     * @param repositoryUrl the configured public repository URL ({@code app.repository.url})
     * @return the {@code api.github.com/repos/{owner}/{repo}/releases} URI, or empty when not a GitHub repository
     */
    public static Optional<URI> githubReleasesApi(final String repositoryUrl) {
        final Matcher matcher = GITHUB_REPOSITORY.matcher(repositoryUrl.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(URI.create(GITHUB_API_BASE + matcher.group(1) + '/' + matcher.group(2) + RELEASES_PATH));
    }

    /**
     * Builds the human-facing release page URL the footer indicator links to. Because the check runs exactly once at startup, it links to the
     * <em>explicit</em> detected release ({@code {repo}/releases/tag/{tag}}) rather than a moving {@code /releases/latest} pointer that could drift
     * past the version the check actually found. When the tag could not be determined it falls back to the repository's releases listing
     * ({@code {repo}/releases}). Tolerates a single trailing slash on the configured repository URL.
     *
     * @param repositoryUrl the configured public repository URL ({@code app.repository.url})
     * @param tag           the detected latest release tag, or {@code null}/blank when it could not be determined
     * @return the release page URL for that tag, or the releases listing when the tag is unknown
     */
    public static String latestReleaseUrl(final String repositoryUrl, final @Nullable String tag) {
        final String trimmed = repositoryUrl.strip();
        final String base = trimmed.endsWith("/")
            ? trimmed.substring(0, trimmed.length() - 1)
            : trimmed;
        if (tag == null || tag.isBlank()) {
            return base + RELEASES_PATH;
        }
        return base + RELEASE_TAG_PATH + tag.strip();
    }

    /**
     * Extracts the latest release tag from a GitHub "list releases" API JSON body - the first {@code tag_name} in the returned array. The list is
     * ordered newest-first, so the first {@code tag_name} is the most recent published release (pre-releases included; drafts excluded for the
     * anonymous caller). A deliberately minimal scan (not a full JSON parse): each release object serialises {@code tag_name} ahead of any free-text
     * field, so the first match is the newest release's tag.
     *
     * @param jsonBody the API response body
     * @return the latest release tag, or empty when absent or blank
     */
    public static Optional<String> extractLatestTag(final String jsonBody) {
        final Matcher matcher = TAG_NAME.matcher(jsonBody);
        if (!matcher.find()) {
            return Optional.empty();
        }
        final String tag = matcher.group(1).strip();
        return tag.isEmpty() ? Optional.empty() : Optional.of(tag);
    }

    /**
     * Whether {@code latestVersion} is a strictly newer release than {@code currentVersion}. Both strings are reduced to their leading dotted numeric
     * core (so a {@code v} prefix or a {@code -SNAPSHOT}/pre-release suffix is ignored) and compared segment by segment, treating a missing segment
     * as zero. A version with no numeric core (e.g. {@code dev}), or one too large to parse, is treated as not-newer so the check never falsely
     * advertises an update.
     *
     * @param currentVersion the running application version
     * @param latestVersion  the latest published release version
     * @return {@code true} when {@code latestVersion} is strictly newer than {@code currentVersion}
     */
    public static boolean isUpdateAvailable(final String currentVersion, final String latestVersion) {
        final Optional<List<Integer>> current = parseVersion(currentVersion);
        final Optional<List<Integer>> latest = parseVersion(latestVersion);
        if (current.isEmpty() || latest.isEmpty()) {
            return false;
        }
        return compare(latest.get(), current.get()) > 0;
    }

    /**
     * Classifies the running version against a (possibly-unknown) latest release into an {@link UpdateStatus} for the footer.
     *
     * @param currentVersion   the running application version
     * @param latestVersion    the latest published release version, or {@code null} when it could not be determined
     * @param latestReleaseUrl the latest-release page URL for the footer link
     * @return the resolved update status
     */
    public static UpdateStatus evaluate(final String currentVersion, final @Nullable String latestVersion, final String latestReleaseUrl) {
        if (latestVersion == null) {
            return new UpdateStatus(UpdateAvailability.UNKNOWN, currentVersion, null, latestReleaseUrl);
        }
        final UpdateAvailability availability = isUpdateAvailable(currentVersion, latestVersion)
            ? UpdateAvailability.UPDATE_AVAILABLE
            : UpdateAvailability.UP_TO_DATE;
        return new UpdateStatus(availability, currentVersion, latestVersion, latestReleaseUrl);
    }

    /**
     * Whether the resolved {@link UpdateStatus} advertises a newer release - the footer's admin-only "update available" signal, shown on every page.
     *
     * @param status the resolved update status
     * @return {@code true} when a newer release than the running version is available
     */
    public static boolean updateAvailable(final UpdateStatus status) {
        return status.availability() == UpdateAvailability.UPDATE_AVAILABLE;
    }

    /**
     * The footer up-arrow indicator's tooltip when an update is available (e.g. {@code Update available - v0.8.0}), or {@code null} when the running
     * version is current or the latest release is unknown - the indicator is then hidden. The separator is a plain hyphen.
     *
     * @param status the resolved update status
     * @return the "update available" tooltip text, or {@code null} when no update is advertised
     */
    public static @Nullable String footerTooltip(final UpdateStatus status) {
        if (!updateAvailable(status)) {
            return null;
        }
        return "Update available - v" + status.latestVersion();
    }

    private static Optional<List<Integer>> parseVersion(final String version) {
        final Matcher matcher = VERSION_CORE.matcher(version);
        if (!matcher.find()) {
            return Optional.empty();
        }

        final String[] segments = matcher.group().split("\\.");
        final List<Integer> parsed = new ArrayList<>(segments.length);
        try {
            for (final String segment : segments) {
                parsed.add(Integer.parseInt(segment));
            }
        } catch (final NumberFormatException e) {
            // A numeric segment too large to fit in an int - treat the whole version as unparseable rather than throwing into the caller.
            return Optional.empty();
        }
        return Optional.of(parsed);
    }

    private static int compare(final List<Integer> left, final List<Integer> right) {
        // Zero-pad both to the same length and compare lexicographically, so a missing trailing segment counts as zero (1.2 == 1.2.0) with no
        // length tie-break. Padding to equal length keeps Arrays.compare purely element-wise.
        final int length = Math.max(left.size(), right.size());
        return Arrays.compare(zeroPad(left, length), zeroPad(right, length));
    }

    private static int[] zeroPad(final List<Integer> segments, final int length) {
        final int[] padded = new int[length];
        for (int i = 0; i < segments.size(); i++) {
            padded[i] = segments.get(i);
        }
        return padded;
    }
}
