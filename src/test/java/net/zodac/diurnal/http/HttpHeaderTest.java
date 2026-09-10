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

import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpHeader}, the catalogue of non-standard header names. A typo in any on-the-wire spelling would silently read or write the
 * wrong header, so each constant's {@link HttpHeader#headerName()} is pinned to its exact value, and the test asserts it covers every constant.
 */
class HttpHeaderTest {

    @Test
    void headerName_matchesTheOnTheWireNameForEveryConstant() {
        final Map<HttpHeader, String> expected = new EnumMap<>(HttpHeader.class);
        expected.put(HttpHeader.CF_CONNECTING_IP, "CF-Connecting-IP");
        expected.put(HttpHeader.X_FORWARDED_FOR, "X-Forwarded-For");
        expected.put(HttpHeader.X_FORWARDED_HOST, "X-Forwarded-Host");
        expected.put(HttpHeader.ORIGIN, "Origin");
        expected.put(HttpHeader.REFERER, "Referer");
        expected.put(HttpHeader.HX_RETARGET, "HX-Retarget");
        expected.put(HttpHeader.HX_RESWAP, "HX-Reswap");
        expected.put(HttpHeader.X_CONTENT_TYPE_OPTIONS, "X-Content-Type-Options");
        expected.put(HttpHeader.X_FRAME_OPTIONS, "X-Frame-Options");
        expected.put(HttpHeader.CONTENT_SECURITY_POLICY, "Content-Security-Policy");
        expected.put(HttpHeader.REFERRER_POLICY, "Referrer-Policy");
        expected.put(HttpHeader.CROSS_ORIGIN_OPENER_POLICY, "Cross-Origin-Opener-Policy");
        expected.put(HttpHeader.CROSS_ORIGIN_RESOURCE_POLICY, "Cross-Origin-Resource-Policy");
        expected.put(HttpHeader.PERMISSIONS_POLICY, "Permissions-Policy");
        expected.put(HttpHeader.X_LOCKOUT_RETRY_AFTER, "X-Lockout-Retry-After");
        expected.put(HttpHeader.X_PASSWORD_ERROR, "X-Password-Error");

        assertThat(expected.keySet())
            .as("the test must pin an expected name for every HttpHeader constant")
            .containsExactlyInAnyOrder(HttpHeader.values());
        for (final Map.Entry<HttpHeader, String> entry : expected.entrySet()) {
            assertThat(entry.getKey().headerName())
                .as("on-the-wire name for %s", entry.getKey())
                .isEqualTo(entry.getValue());
        }
    }
}
