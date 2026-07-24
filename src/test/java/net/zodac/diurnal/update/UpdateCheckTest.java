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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure {@link UpdateCheck} decision core - repository-URL derivation, tag extraction, version comparison and the final
 * {@link UpdateStatus} classification.
 */
class UpdateCheckTest {

    private static final String REPO = "https://github.com/zodac/diurnal";

    // ── githubReleasesApi ─────────────────────────────────────────────────

    @Test
    void githubReleasesApi_gitHubRepository_buildsApiUrl() {
        assertThat(UpdateCheck.githubReleasesApi(REPO))
            .as("expected the derived GitHub releases API URL")
            .map(java.net.URI::toString)
            .contains("https://api.github.com/repos/zodac/diurnal/releases");
    }

    @Test
    void githubReleasesApi_trailingSlash_buildsApiUrl() {
        assertThat(UpdateCheck.githubReleasesApi(REPO + '/'))
            .as("a trailing slash should be tolerated")
            .map(java.net.URI::toString)
            .contains("https://api.github.com/repos/zodac/diurnal/releases");
    }

    @Test
    void githubReleasesApi_dotGitSuffix_buildsApiUrl() {
        assertThat(UpdateCheck.githubReleasesApi(REPO + ".git"))
            .as("a .git suffix should be stripped")
            .map(java.net.URI::toString)
            .contains("https://api.github.com/repos/zodac/diurnal/releases");
    }

    @Test
    void githubReleasesApi_httpScheme_buildsApiUrl() {
        assertThat(UpdateCheck.githubReleasesApi("http://github.com/owner/repo"))
            .as("both http and https schemes are accepted")
            .map(java.net.URI::toString)
            .contains("https://api.github.com/repos/owner/repo/releases");
    }

    @Test
    void githubReleasesApi_nonGitHubHost_isEmpty() {
        assertThat(UpdateCheck.githubReleasesApi("https://gitlab.com/owner/repo"))
            .as("a non-GitHub host is unsupported")
            .isEmpty();
    }

    @Test
    void githubReleasesApi_missingRepositorySegment_isEmpty() {
        assertThat(UpdateCheck.githubReleasesApi("https://github.com/owner"))
            .as("a URL without owner/repo is not a repository URL")
            .isEmpty();
    }

    // ── latestReleaseUrl ──────────────────────────────────────────────────

    @Test
    void latestReleaseUrl_withTag_linksToThatExplicitRelease() {
        assertThat(UpdateCheck.latestReleaseUrl(REPO, "0.8.0"))
            .as("the footer must link to the exact detected release, not a moving /releases/latest pointer")
            .isEqualTo("https://github.com/zodac/diurnal/releases/tag/0.8.0");
    }

    @Test
    void latestReleaseUrl_vPrefixedTag_isUsedVerbatim() {
        assertThat(UpdateCheck.latestReleaseUrl(REPO, "v0.8.0"))
            .as("the tag is used verbatim in the release URL (GitHub tags keep any v prefix)")
            .isEqualTo("https://github.com/zodac/diurnal/releases/tag/v0.8.0");
    }

    @Test
    void latestReleaseUrl_paddedTag_isStripped() {
        assertThat(UpdateCheck.latestReleaseUrl(REPO, "  0.8.0  "))
            .as("surrounding whitespace on the tag should not leak into the URL")
            .isEqualTo("https://github.com/zodac/diurnal/releases/tag/0.8.0");
    }

    @Test
    void latestReleaseUrl_trailingSlashRepository_isStripped() {
        assertThat(UpdateCheck.latestReleaseUrl(REPO + '/', "0.8.0"))
            .as("a trailing slash on the repository URL should not double up")
            .isEqualTo("https://github.com/zodac/diurnal/releases/tag/0.8.0");
    }

    @Test
    void latestReleaseUrl_nullTag_fallsBackToReleasesListing() {
        assertThat(UpdateCheck.latestReleaseUrl(REPO, null))
            .as("an unknown tag falls back to the releases listing")
            .isEqualTo("https://github.com/zodac/diurnal/releases");
    }

    @Test
    void latestReleaseUrl_blankTag_fallsBackToReleasesListing() {
        assertThat(UpdateCheck.latestReleaseUrl(REPO, "   "))
            .as("a blank tag falls back to the releases listing")
            .isEqualTo("https://github.com/zodac/diurnal/releases");
    }

    // ── extractLatestTag ──────────────────────────────────────────────────

    @Test
    void extractLatestTag_present_returnsTag() {
        assertThat(UpdateCheck.extractLatestTag("{\"tag_name\": \"0.8.0\", \"name\": \"Release\"}"))
            .as("expected the tag_name value")
            .contains("0.8.0");
    }

    @Test
    void extractLatestTag_absent_isEmpty() {
        assertThat(UpdateCheck.extractLatestTag("{\"name\": \"Release\"}"))
            .as("no tag_name field is present")
            .isEmpty();
    }

    @Test
    void extractLatestTag_blankValue_isEmpty() {
        assertThat(UpdateCheck.extractLatestTag("{\"tag_name\": \"   \"}"))
            .as("a blank tag_name is treated as absent")
            .isEmpty();
    }

    @Test
    void extractLatestTag_listOfReleases_returnsNewestFirst() {
        // The /releases list endpoint returns an array ordered newest-first; the first tag_name is the most recent release (even a pre-release).
        final String body = "[{\"tag_name\": \"0.7.2\", \"prerelease\": true}, {\"tag_name\": \"0.7.1\", \"prerelease\": true}]";
        assertThat(UpdateCheck.extractLatestTag(body))
            .as("the newest release is the first element of the list")
            .contains("0.7.2");
    }

    // ── isUpdateAvailable ─────────────────────────────────────────────────

    @Test
    void isUpdateAvailable_newerMajor_isTrue() {
        assertThat(UpdateCheck.isUpdateAvailable("1.2.3", "2.0.0"))
            .as("a newer major version is an update")
            .isTrue();
    }

    @Test
    void isUpdateAvailable_newerMinor_isTrue() {
        assertThat(UpdateCheck.isUpdateAvailable("1.1.0", "1.2.0"))
            .as("a newer minor version is an update")
            .isTrue();
    }

    @Test
    void isUpdateAvailable_newerPatch_isTrue() {
        assertThat(UpdateCheck.isUpdateAvailable("1.1.1", "1.1.2"))
            .as("a newer patch version is an update")
            .isTrue();
    }

    @Test
    void isUpdateAvailable_vPrefixedTag_isTrue() {
        assertThat(UpdateCheck.isUpdateAvailable("0.7.2", "v0.8.0"))
            .as("a v-prefixed tag is compared on its numeric core")
            .isTrue();
    }

    @Test
    void isUpdateAvailable_extraTrailingSegment_isTrue() {
        assertThat(UpdateCheck.isUpdateAvailable("1.2", "1.2.1"))
            .as("a longer, higher version is newer")
            .isTrue();
    }

    @Test
    void isUpdateAvailable_currentLongerButHigher_isFalse() {
        assertThat(UpdateCheck.isUpdateAvailable("1.2.1", "1.2"))
            .as("the running version already has the extra segment")
            .isFalse();
    }

    @Test
    void isUpdateAvailable_equalVersions_isFalse() {
        assertThat(UpdateCheck.isUpdateAvailable("1.2.3", "1.2.3"))
            .as("the same version is not an update")
            .isFalse();
    }

    @Test
    void isUpdateAvailable_paddedEqualVersions_isFalse() {
        assertThat(UpdateCheck.isUpdateAvailable("1.2", "1.2.0"))
            .as("a missing trailing segment counts as zero")
            .isFalse();
    }

    @Test
    void isUpdateAvailable_olderLatest_isFalse() {
        assertThat(UpdateCheck.isUpdateAvailable("2.0.0", "1.9.9"))
            .as("an older latest is not an update")
            .isFalse();
    }

    @Test
    void isUpdateAvailable_unparseableCurrent_isFalse() {
        assertThat(UpdateCheck.isUpdateAvailable("dev", "1.0.0"))
            .as("a non-numeric running version never advertises an update")
            .isFalse();
    }

    @Test
    void isUpdateAvailable_unparseableLatest_isFalse() {
        assertThat(UpdateCheck.isUpdateAvailable("1.0.0", "nightly"))
            .as("a non-numeric latest tag never advertises an update")
            .isFalse();
    }

    @Test
    void isUpdateAvailable_overflowingSegment_isFalse() {
        assertThat(UpdateCheck.isUpdateAvailable("1.0.0", "99999999999999999999.0"))
            .as("a segment too large to parse is treated as unparseable, not newer")
            .isFalse();
    }

    // ── evaluate ──────────────────────────────────────────────────────────

    @Test
    void evaluate_nullLatest_isUnknown() {
        final UpdateStatus status = UpdateCheck.evaluate("0.7.2", null, "url");
        assertThat(status.availability())
            .as("an unknown latest version is UNKNOWN")
            .isEqualTo(UpdateAvailability.UNKNOWN);
        assertThat(status.latestVersion())
            .as("no latest version is carried when unknown")
            .isNull();
        assertThat(status.currentVersion())
            .as("the current version is carried through")
            .isEqualTo("0.7.2");
        assertThat(status.latestReleaseUrl())
            .as("the release URL is carried through")
            .isEqualTo("url");
    }

    @Test
    void evaluate_newerLatest_isUpdateAvailable() {
        final UpdateStatus status = UpdateCheck.evaluate("0.7.2", "0.8.0", "url");
        assertThat(status.availability())
            .as("a newer latest version is UPDATE_AVAILABLE")
            .isEqualTo(UpdateAvailability.UPDATE_AVAILABLE);
        assertThat(status.latestVersion())
            .as("the latest version is carried")
            .isEqualTo("0.8.0");
    }

    @Test
    void evaluate_sameLatest_isUpToDate() {
        final UpdateStatus status = UpdateCheck.evaluate("0.8.0", "0.8.0", "url");
        assertThat(status.availability())
            .as("the same latest version is UP_TO_DATE")
            .isEqualTo(UpdateAvailability.UP_TO_DATE);
        assertThat(status.latestVersion())
            .as("the latest version is still carried when up to date")
            .isEqualTo("0.8.0");
    }

    // ── footer signals (updateAvailable / footerTooltip) ────────────────────

    @Test
    void updateAvailable_updateAvailableStatus_isTrue() {
        assertThat(UpdateCheck.updateAvailable(UpdateCheck.evaluate("0.7.2", "0.8.0", "url")))
            .as("an UPDATE_AVAILABLE status advertises an update")
            .isTrue();
    }

    @Test
    void updateAvailable_upToDateStatus_isFalse() {
        assertThat(UpdateCheck.updateAvailable(UpdateCheck.evaluate("0.8.0", "0.8.0", "url")))
            .as("an UP_TO_DATE status advertises no update")
            .isFalse();
    }

    @Test
    void updateAvailable_unknownStatus_isFalse() {
        assertThat(UpdateCheck.updateAvailable(UpdateCheck.evaluate("0.7.2", null, "url")))
            .as("an UNKNOWN status advertises no update")
            .isFalse();
    }

    @Test
    void footerTooltip_updateAvailable_composesTooltip() {
        assertThat(UpdateCheck.footerTooltip(UpdateCheck.evaluate("0.7.2", "0.8.0", "url")))
            .as("the tooltip names the available version with a plain hyphen separator")
            .isEqualTo("Update available - v0.8.0");
    }

    @Test
    void footerTooltip_upToDate_isNull() {
        assertThat(UpdateCheck.footerTooltip(UpdateCheck.evaluate("0.8.0", "0.8.0", "url")))
            .as("no tooltip is composed when the running version is current")
            .isNull();
    }

    @Test
    void footerTooltip_unknown_isNull() {
        assertThat(UpdateCheck.footerTooltip(UpdateCheck.evaluate("0.7.2", null, "url")))
            .as("no tooltip is composed when the latest release is unknown")
            .isNull();
    }
}
