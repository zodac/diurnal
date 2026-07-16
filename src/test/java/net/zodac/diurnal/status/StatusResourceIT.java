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

package net.zodac.diurnal.status;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.config.ReleaseVersion;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@code GET /api/v1/status}: the endpoint is anonymous, reports a ready application against the live test database (200 with
 * liveness/readiness both {@code UP}), and exposes the running version plus an {@code HH:mm:ss.SSS} uptime.
 */
@QuarkusTest
class StatusResourceIT extends IntegrationTestBase {

    @Test
    void status_anonymousRequest_reportsReady() {
        given().accept(ContentType.JSON)
            .get("/api/v1/status")
            .then().statusCode(200)
            .body("liveness", equalTo("UP"))
            .body("readiness", equalTo("UP"))
            // The version is served from the packaged VERSION resource (same source as the footer / OpenAPI info block).
            .body("version", equalTo(ReleaseVersion.resolve("fallback-unused")))
            // HH:mm:ss.SSS with leading zero-value groups omitted: 1-3 dot-free groups, always ending in .SSS.
            .body("uptime", matchesRegex("\\d+(:\\d{2}){0,2}\\.\\d{3}"));
    }
}
