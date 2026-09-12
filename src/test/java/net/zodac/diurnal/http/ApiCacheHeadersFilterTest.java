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

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApiCacheHeadersFilter#needsPrivateDirective(String, boolean)}, the decision behind the default caching directive on the
 * public API: every {@code /api/v1} response carries private per-user data and no configured response filter touches that namespace, so one is added
 * here unless the resource attached its own.
 */
class ApiCacheHeadersFilterTest {

    private static final String API_PATH = "api/v1/data/export";

    @Test
    void needsPrivateDirective_addsToAnUndirectedApiResponse() {
        assertThat(ApiCacheHeadersFilter.needsPrivateDirective(API_PATH, false))
            .as("an API response with no caching directive should be given the private one")
            .isTrue();
    }

    @Test
    void needsPrivateDirective_leavesDirectedApiResponseAlone() {
        // The validated reads set `private, no-cache` themselves, and need the browser to keep a stored copy for their ETag to earn a 304 -
        // overwriting that with `no-store` would silently undo the conditional-GET support.
        assertThat(ApiCacheHeadersFilter.needsPrivateDirective(API_PATH, true))
            .as("an API response that set its own directive should be left alone")
            .isFalse();
    }

    @Test
    void needsPrivateDirective_addsWithLeadingSlash() {
        assertThat(ApiCacheHeadersFilter.needsPrivateDirective("/api/v1/stats", false))
            .as("a leading slash should not change the answer")
            .isTrue();
    }

    @Test
    void needsPrivateDirective_ignoresPageRoute() {
        // The pages are marked `no-store` by the html-pages response filter, which is where that rule belongs.
        assertThat(ApiCacheHeadersFilter.needsPrivateDirective("settings", false))
            .as("a page route is directed by the configured response filter, not by this one")
            .isFalse();
    }

    @Test
    void needsPrivateDirective_ignoresAnInternalFragment() {
        // The /internal/ fragments are `no-cache` by the html-fragments filter, deliberately NOT `no-store`, so their ETags can still earn a 304.
        assertThat(ApiCacheHeadersFilter.needsPrivateDirective("internal/notes/list", false))
            .as("an internal fragment is directed by the configured response filter, not by this one")
            .isFalse();
    }

    @Test
    void needsPrivateDirective_ignoresAnUnversionedApiPath() {
        // The Swagger UI shell lives at /api and never reaches a JAX-RS response filter, but the prefix is pinned to the versioned namespace
        // regardless, so this filter can only ever speak for the REST API itself.
        assertThat(ApiCacheHeadersFilter.needsPrivateDirective("api", false))
            .as("only the versioned API namespace is covered")
            .isFalse();
    }
}
