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

import java.util.Optional;
import net.zodac.diurnal.stub.StubAppConfig;
import net.zodac.diurnal.stub.StubUpdateCheckConfig;
import org.junit.jupiter.api.Test;

/**
 * The half of {@link GitHubLatestReleaseClient} that answers without leaving the process: a repository URL that is not a GitHub one is never looked
 * up at all.
 *
 * <p>
 * The other half - the request itself - is deliberately not covered here. The releases URL is derived from the repository URL and always resolves to
 * {@code api.github.com}, so exercising it would mean a test that reaches the public internet: slow, offline-fragile, and rate-limited by the
 * provider. What the response is then made of is pure and unit-tested in {@link UpdateCheckTest} ({@code extractLatestTag}), which is the split this
 * seam exists for.
 */
class GitHubLatestReleaseClientTest {

    @Test
    void latestReleaseVersion_repositoryUrlThatIsNotGitHub_isEmptyAndMakesNoRequest() {
        final GitHubLatestReleaseClient client = clientFor("https://diurnal.example.com/zodac/diurnal");

        assertThat(client.latestReleaseVersion())
            .as("a self-hosted or mirrored repository has no releases API to ask, so the check is skipped rather than attempted")
            .isEmpty();
    }

    @Test
    void latestReleaseVersion_noRepositoryUrlConfigured_isEmptyAndMakesNoRequest() {
        final GitHubLatestReleaseClient client = clientFor("");

        assertThat(client.latestReleaseVersion())
            .as("an unset app.repository.url names nothing to look up")
            .isEmpty();
    }

    private static GitHubLatestReleaseClient clientFor(final String repositoryUrl) {
        return new GitHubLatestReleaseClient(new StubAppConfig(repositoryUrl, "", Optional.empty()), new StubUpdateCheckConfig());
    }
}
