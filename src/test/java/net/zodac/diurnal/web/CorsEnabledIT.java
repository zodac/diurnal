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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Verifies the CORS opt-in: with {@code CORS_ALLOWED_ORIGINS} configured (here via a test profile), a browser app on that origin is approved —
 * responses to it carry {@code Access-Control-Allow-Origin} — while every other origin remains blocked.
 */
@QuarkusTest
@TestProfile(CorsEnabledProfile.class)
class CorsEnabledIT extends IntegrationTestBase {

    private static final String ALLOWED_ORIGIN = CorsEnabledProfile.ALLOWED_ORIGIN;

    @Test
    void allowedOrigin_getsCorsAllowHeader() {
        given().header("Origin", ALLOWED_ORIGIN)
                .get("/api/v1/auth/login")
                .then()
                .header("Access-Control-Allow-Origin", equalTo(ALLOWED_ORIGIN));
    }

    @Test
    void otherOrigin_staysBlocked() {
        given().header("Origin", "https://not-allowed.example.com")
                .get("/api/v1/auth/login")
                .then()
                .header("Access-Control-Allow-Origin", nullValue());
    }
}
