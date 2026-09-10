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

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.UNAUTHORIZED;

import io.quarkus.test.junit.QuarkusTest;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link RequestBodyLimitFilter} end to end: a request body over {@code app.http.max-request-body} is refused with {@code 413} on an
 * ordinary endpoint, before it is read, while the data-import endpoints are exempt so a re-imported export can use the larger HTTP-layer ceiling. The
 * test profile keeps the shipped 1M cap and the 100M ceiling, so a 1.1MB body clears the ceiling and is judged only by this filter.
 */
@QuarkusTest
class RequestBodyLimitIT extends IntegrationTestBase {

    // 413 (REQUEST_ENTITY_TOO_LARGE) - not present in HttpStatusCodes, which mirrors only the codes other suites assert.
    private static final int REQUEST_ENTITY_TOO_LARGE = 413;
    private static final int ONE_POINT_ONE_MEGABYTES = 1_100_000;

    @Override
    protected void createDbState() {
        // A seeded account takes the app out of first-run mode; the endpoints under test are anonymous, but this keeps the fixture consistent.
        newUser("body-limit-it@lt.test", "Body Limit User");
    }

    @Test
    void oversizedBodyOnAnOrdinaryEndpoint_isRejectedWith413() {
        given()
            .contentType("application/json")
            .body(new byte[ONE_POINT_ONE_MEGABYTES])
            .post("/api/v1/auth/login")
            .then()
            .statusCode(REQUEST_ENTITY_TOO_LARGE);
    }

    @Test
    void smallBodyOnAnOrdinaryEndpoint_isNotRejectedByTheCap() {
        // A well-formed but wrong login is far under the cap, so it passes the filter and reaches authentication (401), proving the cap does not
        // block ordinary requests.
        given()
            .contentType("application/json")
            .body("{\"email\":\"body-limit-nobody@lt.test\",\"password\":\"wrong-password-value\"}")
            .post("/api/v1/auth/login")
            .then()
            .statusCode(UNAUTHORIZED);
    }

    @Test
    void oversizedBodyOnTheImportEndpoint_isExemptFromTheCap() {
        // The same 1.1MB body that is rejected above is allowed past the cap here; the anonymous request is then challenged by security (401), NOT
        // refused as too large - proving the import endpoint keeps the larger ceiling.
        given()
            .contentType("application/zip")
            .body(new byte[ONE_POINT_ONE_MEGABYTES])
            .post("/api/v1/data/import")
            .then()
            .statusCode(UNAUTHORIZED);
    }
}
