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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecurityHeadersFilterTest {

    // ── No origins configured ─────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", ","})
    void buildFrameAncestorsCsp_blankOrEmptyInput_returnsSelfOnly(final String input) {
        assertThat(SecurityHeadersFilter.buildFrameAncestorsCsp(input))
            .as("unexpected value")
            .isEqualTo("frame-ancestors 'self'");
    }

    // ── Single origin ─────────────────────────────────────────────────────────

    @Test
    void buildFrameAncestorsCsp_singleOrigin_appendedAfterSelf() {
        assertThat(SecurityHeadersFilter.buildFrameAncestorsCsp("https://diurnal.example.com"))
            .as("unexpected value")
            .isEqualTo("frame-ancestors 'self' https://diurnal.example.com");
    }

    @Test
    void buildFrameAncestorsCsp_singleOriginWithPort_appendedAfterSelf() {
        assertThat(SecurityHeadersFilter.buildFrameAncestorsCsp("http://127.0.0.1:8080"))
            .as("unexpected value")
            .isEqualTo("frame-ancestors 'self' http://127.0.0.1:8080");
    }

    // ── Multiple origins ──────────────────────────────────────────────────────

    @Test
    void buildFrameAncestorsCsp_multipleOrigins_allAppendedSpaceSeparated() {
        assertThat(SecurityHeadersFilter.buildFrameAncestorsCsp("https://diurnal.example.com,http://127.0.0.1:8080"))
            .as("unexpected value")
            .isEqualTo("frame-ancestors 'self' https://diurnal.example.com http://127.0.0.1:8080");
    }

    // ── Whitespace handling ───────────────────────────────────────────────────

    @Test
    void buildFrameAncestorsCsp_whitespaceAroundCommas_stripped() {
        assertThat(SecurityHeadersFilter.buildFrameAncestorsCsp("https://diurnal.example.com , http://127.0.0.1:8080"))
            .as("unexpected value")
            .isEqualTo("frame-ancestors 'self' https://diurnal.example.com http://127.0.0.1:8080");
    }

    @Test
    void buildFrameAncestorsCsp_trailingComma_ignored() {
        assertThat(SecurityHeadersFilter.buildFrameAncestorsCsp("https://diurnal.example.com,"))
            .as("unexpected value")
            .isEqualTo("frame-ancestors 'self' https://diurnal.example.com");
    }

    @Test
    void buildFrameAncestorsCsp_emptySegmentBetweenOrigins_dropped() {
        // A double comma yields an empty token mid-list (unlike a trailing comma, which split() drops):
        // it must be filtered out so the survivors stay single-space separated, not double-spaced.
        assertThat(SecurityHeadersFilter.buildFrameAncestorsCsp("https://diurnal.example.com,,http://127.0.0.1:8080"))
            .as("empty inner segment should be dropped, leaving single-space separation")
            .isEqualTo("frame-ancestors 'self' https://diurnal.example.com http://127.0.0.1:8080");
    }
}
