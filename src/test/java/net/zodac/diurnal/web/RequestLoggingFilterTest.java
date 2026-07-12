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

package net.zodac.diurnal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RequestLoggingFilter#elapsedMillis(Object)}: a recorded start marker yields a
 * non-negative whole-millisecond count, while a missing or wrong-typed marker degrades to {@code "?"}.
 */
class RequestLoggingFilterTest {

    @Test
    void elapsedMillis_withStartMarker_isNonNegativeNumber() {
        final long start = System.nanoTime() - 5_000_000L; // ~5ms ago
        final String elapsed = RequestLoggingFilter.elapsedMillis(start);
        assertThat(Long.parseLong(elapsed))
            .as("elapsed millis from a past start marker should be a non-negative number")
            .isGreaterThanOrEqualTo(0L);
    }

    @Test
    void millisBetween_convertsNanosToWholeMillis() {
        assertThat(RequestLoggingFilter.millisBetween(2_000_000L, 5_000_000L))
            .as("3ms elapsed (5ms - 2ms) should render as 3 whole millis")
            .isEqualTo(3L);
    }

    @Test
    void millisBetween_truncatesSubMillisRemainder() {
        assertThat(RequestLoggingFilter.millisBetween(0L, 2_500_000L))
            .as("2.5ms elapsed should truncate to 2 whole millis")
            .isEqualTo(2L);
    }

    @Test
    void elapsedMillis_withNullMarker_isUnknown() {
        assertThat(RequestLoggingFilter.elapsedMillis(null))
            .as("a missing start marker should render as the unknown placeholder")
            .isEqualTo("?");
    }

    @Test
    void elapsedMillis_withNonLongMarker_isUnknown() {
        assertThat(RequestLoggingFilter.elapsedMillis("not-a-long"))
            .as("a non-Long start marker should render as the unknown placeholder")
            .isEqualTo("?");
    }

    @Test
    void shouldLog_healthProbe_isExcluded() {
        assertThat(RequestLoggingFilter.shouldLog("health"))
            .as("the container health-check path must never be logged")
            .isFalse();
    }

    @Test
    void shouldLog_healthProbeWithLeadingSlash_isExcluded() {
        assertThat(RequestLoggingFilter.shouldLog("/health"))
            .as("the health-check path must be excluded regardless of a leading slash")
            .isFalse();
    }

    @Test
    void shouldLog_regularEndpoint_isLogged() {
        assertThat(RequestLoggingFilter.shouldLog("actions/list"))
            .as("a normal application endpoint must be logged")
            .isTrue();
    }
}
