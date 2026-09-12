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

package net.zodac.diurnal.stub;

import io.quarkus.runtime.configuration.MemorySize;
import java.math.BigInteger;
import java.util.Optional;
import net.zodac.diurnal.config.AppConfig;

/**
 * Reusable {@link AppConfig} stub for unit tests: every value is supplied through the record components, with {@link #timezone()} fixed to
 * {@code UTC}. Use {@link #empty()} when the individual values do not matter to the test. The served-asset settings are a separate mapping, stubbed
 * by {@link StubAssetsConfig}.
 *
 * @param repositoryUrl the source repository base URL
 * @param buildTimestamp the ISO-8601 build timestamp (its leading four digits are the build year)
 * @param basePath the URL prefix the deployment is mounted under, or empty for the origin root
 */
public record StubAppConfig(String repositoryUrl, String buildTimestamp, Optional<String> basePath) implements AppConfig {

    private static final long DEFAULT_MAX_REQUEST_BODY_BYTES = 1_048_576L;

    /**
     * A stub with blank strings, for tests that do not care about any {@code app.*} value.
     *
     * @return an inert {@link StubAppConfig}
     */
    public static StubAppConfig empty() {
        return new StubAppConfig("", "", Optional.empty());
    }

    /**
     * A stub carrying only a base path, for a test exercising a sub-path deployment.
     *
     * @param basePath the URL prefix the deployment is mounted under
     * @return a {@link StubAppConfig} with that base path and nothing else
     */
    public static StubAppConfig withBasePath(final String basePath) {
        return new StubAppConfig("", "", Optional.of(basePath));
    }

    @Override
    public String timezone() {
        return "UTC";
    }

    @Override
    public boolean trustForwardedHeaders() {
        return false;
    }

    @Override
    public boolean trustCloudflareHeader() {
        return false;
    }

    // MemorySize(BigInteger) is marked for removal, but it is the ONLY public constructor this Quarkus version exposes and the type has no
    // factory method - there is nothing to migrate to until one is added upstream.
    @SuppressWarnings("removal")
    @Override
    public MemorySize maxRequestBody() {
        return new MemorySize(BigInteger.valueOf(DEFAULT_MAX_REQUEST_BODY_BYTES));
    }
}
