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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Verifies the CORS default: the filter is compiled in ({@code quarkus.http.cors.enabled=true}) but {@code CORS_ALLOWED_ORIGINS} is unset, so NO
 * cross-origin browser caller is allowed — a request carrying a foreign {@code Origin} gets no {@code Access-Control-Allow-Origin} back, and the
 * browser blocks the response. {@link CorsEnabledIT} covers the opt-in behaviour with an origin configured.
 */
@QuarkusTest
class CorsIT extends IntegrationTestBase {

    @Test
    void foreignOrigin_getsNoCorsAllowHeader_byDefault() {
        given().header("Origin", "https://not-allowed.example.com")
                .get("/api/v1/auth/login")
                .then()
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void preflight_fromForeignOrigin_isNotApproved_byDefault() {
        given().header("Origin", "https://not-allowed.example.com")
                .header("Access-Control-Request-Method", "POST")
                .options("/api/v1/auth/login")
                .then()
                .header("Access-Control-Allow-Origin", nullValue());
    }
}
