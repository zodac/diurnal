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

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApplicationVersion}, the single accessor for the running release version. The packaged {@code VERSION} resource is on the
 * test classpath, so {@link ApplicationVersion#release()} resolves that in preference to the Maven project version fallback supplied by the stubbed
 * {@link QuarkusApplicationConfig}.
 */
class ApplicationVersionTest {

    @Test
    void release_prefersPackagedVersionOverMavenFallback() {
        final ApplicationVersion applicationVersion = versionWith();
        assertThat(applicationVersion.release())
            .as("the packaged VERSION resource should be used, not the Maven project version fallback")
            .isNotEqualTo("0.0.1-SNAPSHOT")
            .isNotBlank();
    }

    private static ApplicationVersion versionWith() {
        return new ApplicationVersion(() -> "0.0.1-SNAPSHOT");
    }
}
