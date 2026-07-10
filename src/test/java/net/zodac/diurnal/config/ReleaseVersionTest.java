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

package net.zodac.diurnal.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReleaseVersion}, the shared reader for the packaged {@code VERSION} resource.
 * Most tests substitute the stream to exercise the trim/fallback branching in isolation; one asserts the
 * real resource is on the classpath.
 */
class ReleaseVersionTest {

    private static InputStream streamOf(final String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void resolve_readsResource() {
        // resolve(String) reads the packaged /VERSION resource, so it matches the file content, not the fallback.
        final String fromFile = ReleaseVersion.resolve(streamOf("1.2.3\n"), "unused-fallback");
        assertThat(ReleaseVersion.resolve("unused-fallback"))
            .as("the packaged VERSION resource should be used, not the fallback")
            .isNotEqualTo("unused-fallback")
            .isNotBlank();
        assertThat(fromFile)
            .as("the stream content should be trimmed and used")
            .isEqualTo("1.2.3");
    }

    @Test
    void openResource_findsPackagedFile() {
        // The POM packages the repo-root VERSION file onto the classpath, so it is resolvable at runtime.
        try (InputStream stream = ReleaseVersion.openResource()) {
            assertThat(stream)
                .as("the packaged VERSION resource should be on the classpath")
                .isNotNull();
        } catch (final IOException e) {
            throw new AssertionError("closing the VERSION resource should not fail", e);
        }
    }

    @Test
    void resolve_nullStream_returnsFallback() {
        assertThat(ReleaseVersion.resolve(null, "fallback"))
            .as("a null stream should yield the fallback")
            .isEqualTo("fallback");
    }

    @Test
    void resolve_trimsContent() {
        assertThat(ReleaseVersion.resolve(streamOf("  0.4.2\n"), "fallback"))
            .as("surrounding whitespace should be trimmed from the version")
            .isEqualTo("0.4.2");
    }

    @Test
    void resolve_blankContent_returnsFallback() {
        assertThat(ReleaseVersion.resolve(streamOf("   \n"), "fallback"))
            .as("blank content should yield the fallback")
            .isEqualTo("fallback");
    }

    @Test
    void resolve_readFailure_returnsFallback() throws IOException {
        try (InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }

            @Override
            public byte[] readAllBytes() throws IOException {
                throw new IOException("boom");
            }
        }) {
            assertThat(ReleaseVersion.resolve(failing, "fallback"))
                .as("an unreadable stream should yield the fallback")
                .isEqualTo("fallback");
        }
    }
}
