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
 * Unit tests for {@link RequestBodyLimitFilter#exceedsLimit(String, String, long)}, the decision behind the per-request body cap: a
 * {@code Content-Length} over the configured limit is rejected on every endpoint EXCEPT the data-import endpoints, which are exempt so a re-imported
 * export can use the larger {@code quarkus.http.limits.max-body-size} ceiling.
 */
class RequestBodyLimitFilterTest {

    private static final long LIMIT = 1000L;
    private static final String NORMAL_PATH = "api/v1/auth/login";

    @Test
    void exceedsLimit_rejectsBodyOverTheLimit() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "1001", LIMIT))
            .as("a Content-Length above the limit should be rejected")
            .isTrue();
    }

    @Test
    void exceedsLimit_allowsBodyExactlyAtTheLimit() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "1000", LIMIT))
            .as("a Content-Length equal to the limit should be allowed (the bound is exclusive)")
            .isFalse();
    }

    @Test
    void exceedsLimit_allowsBodyUnderTheLimit() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "999", LIMIT))
            .as("a Content-Length below the limit should be allowed")
            .isFalse();
    }

    @Test
    void exceedsLimit_allowsWhenContentLengthAbsent() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, null, LIMIT))
            .as("a request with no Content-Length is not measured here and is allowed")
            .isFalse();
    }

    @Test
    void exceedsLimit_allowsWhenContentLengthBlank() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "   ", LIMIT))
            .as("a blank Content-Length should be treated as absent, not as zero-or-huge")
            .isFalse();
    }

    @Test
    void exceedsLimit_allowsWhenContentLengthNotNumeric() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "not-a-number", LIMIT))
            .as("an unparseable Content-Length should not trip the cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_exemptsApiImportEndpoint() {
        assertThat(RequestBodyLimitFilter.exceedsLimit("api/v1/data/import", "999999999", LIMIT))
            .as("the public API import endpoint is exempt from the per-request cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_exemptsApiImportPreviewEndpoint() {
        assertThat(RequestBodyLimitFilter.exceedsLimit("api/v1/data/import/preview", "999999999", LIMIT))
            .as("the public API import-preview endpoint is exempt from the per-request cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_exemptsInternalImportEndpoint() {
        assertThat(RequestBodyLimitFilter.exceedsLimit("internal/data/import", "999999999", LIMIT))
            .as("the web UI internal import endpoint is exempt from the per-request cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_exemptsImportPathWithLeadingSlash() {
        assertThat(RequestBodyLimitFilter.exceedsLimit("/api/v1/data/import", "999999999", LIMIT))
            .as("a leading slash on the path should not defeat the import exemption")
            .isFalse();
    }

    @Test
    void exceedsLimit_isDisabledWhenLimitIsZero() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "999999999", 0L))
            .as("a limit of zero disables the cap")
            .isFalse();
    }

    @Test
    void exceedsLimit_isDisabledWhenLimitIsNegative() {
        assertThat(RequestBodyLimitFilter.exceedsLimit(NORMAL_PATH, "999999999", -1L))
            .as("a negative limit disables the cap")
            .isFalse();
    }
}
