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

    private static final String REPO = "https://github.com/zodac-personal/diurnal";

    // ── githubReleasesApi ─────────────────────────────────────────────────

    @Test
    void githubReleasesApi_gitHubRepository_buildsApiUrl() {
        assertThat(UpdateCheck.githubReleasesApi(REPO))
            .as("expected the derived GitHub releases API URL")
            .map(java.net.URI::toString)
            .contains("https://api.github.com/repos/zodac-personal/diurnal/releases/latest");
    }

    @Test
    void githubReleasesApi_trailingSlash_buildsApiUrl() {
        assertThat(UpdateCheck.githubReleasesApi(REPO + '/'))
            .as("a trailing slash should be tolerated")
            .map(java.net.URI::toString)
            .contains("https://api.github.com/repos/zodac-personal/diurnal/releases/latest");
    }

    @Test
    void githubReleasesApi_dotGitSuffix_buildsApiUrl() {
        assertThat(UpdateCheck.githubReleasesApi(REPO + ".git"))
            .as("a .git suffix should be stripped")
            .map(java.net.URI::toString)
            .contains("https://api.github.com/repos/zodac-personal/diurnal/releases/latest");
    }

    @Test
    void githubReleasesApi_httpScheme_buildsApiUrl() {
        assertThat(UpdateCheck.githubReleasesApi("http://github.com/owner/repo"))
            .as("both http and https schemes are accepted")
            .map(java.net.URI::toString)
            .contains("https://api.github.com/repos/owner/repo/releases/latest");
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
    void latestReleaseUrl_noTrailingSlash_appendsPath() {
        assertThat(UpdateCheck.latestReleaseUrl(REPO))
            .as("unexpected latest-release URL")
            .isEqualTo("https://github.com/zodac-personal/diurnal/releases/latest");
    }

    @Test
    void latestReleaseUrl_trailingSlash_isStripped() {
        assertThat(UpdateCheck.latestReleaseUrl(REPO + '/'))
            .as("a trailing slash should not double up")
            .isEqualTo("https://github.com/zodac-personal/diurnal/releases/latest");
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
}
