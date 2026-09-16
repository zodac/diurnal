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

package net.zodac.diurnal.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ErrorCacheHeadersFilter#mustNotBeStored(int)}, the decision that stops a failure being cached: the static-asset cache
 * headers are keyed on PATH, so a {@code 403} or {@code 404} under {@code /fonts/} would otherwise go out carrying a seven-day public directive and
 * outlive whatever caused it.
 */
class ErrorCacheHeadersFilterTest {

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 413, 422, 429, 500, 503})
    void mustNotBeStored_stripsEveryClientAndServerFailure(final int statusCode) {
        assertThat(ErrorCacheHeadersFilter.mustNotBeStored(statusCode))
            .as("a response reporting failure must never be stored, whatever path-keyed directive it picked up")
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 204, 206})
    void mustNotBeStored_leavesSuccessfulResponsesAlone(final int statusCode) {
        assertThat(ErrorCacheHeadersFilter.mustNotBeStored(statusCode))
            .as("a real asset keeps the caching it was configured with - stripping it would be the whole point undone")
            .isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 304, 307})
    void mustNotBeStored_leavesRedirectsAndNotModifiedAlone(final int statusCode) {
        // A 304 has to keep the directive that earned it, or the conditional GET loses the stored copy its ETag revalidates against; a redirect is
        // legitimately cacheable. Only a FAILURE is stripped.
        assertThat(ErrorCacheHeadersFilter.mustNotBeStored(statusCode))
            .as("3xx is deliberately untouched, since 304 caching is load-bearing for every ETag in the app")
            .isFalse();
    }
}
