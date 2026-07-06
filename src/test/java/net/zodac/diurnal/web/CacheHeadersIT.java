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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Verifies the HTTP cache-control strategy configured in {@code application.properties}: the
 * content-hashed stylesheet is cached immutably for a year, the stable-URL img/font assets keep a
 * one-day ceiling, and dynamic HTML pages are marked {@code no-cache} so a reverse proxy never serves
 * a stale page pointing at an obsolete asset URL.
 *
 * <p>Runs under the {@code test} profile, which (unlike {@code dev}) does not relax these headers, so
 * the production caching behaviour is exercised. The served stylesheet is the un-hashed {@code app.css}
 * here, since the content-hash rename happens only in the Docker build.
 */
@QuarkusTest
class CacheHeadersIT extends IntegrationTestBase {

    @Override
    protected void createDbState() {
        // A seeded user takes the app out of first-run mode so /login renders a 200 HTML page (rather
        // than redirecting to /welcome), letting us assert the page's Cache-Control header directly.
        newUser("cache-it@lt.test", "Cache User");
    }

    @Test
    void stylesheet_isCachedImmutablyForAYear() {
        given().get("/css/app.css")
                .then().statusCode(200)
                .header("Cache-Control", containsString("immutable"))
                .header("Cache-Control", containsString("max-age=31536000"));
    }

    @Test
    void sharedScript_isCachedImmutablyForAYear() {
        // The extracted shared behaviour (app.js), like the stylesheet, is served under a content-hashed
        // filename in the image, so its /js/ URL is cached a year as immutable (same filter covers htmx +
        // the dashboard engine). Dev serves the un-hashed app.js, but under the same immutable filter here.
        given().get("/js/app.js")
                .then().statusCode(200)
                .header("Cache-Control", containsString("immutable"))
                .header("Cache-Control", containsString("max-age=31536000"));
    }

    @Test
    void staticImageAsset_hasOneDayCacheCeilingNotImmutable() {
        // Stable-URL assets (no content hash) must NOT be immutable — a redeploy reuses the URL, so a
        // one-day ceiling bounds how long a changed image/font can be served stale.
        given().get("/img/favicon.svg")
                .then().statusCode(200)
                .header("Cache-Control", containsString("max-age=86400"))
                .header("Cache-Control", not(containsString("immutable")));
    }

    @Test
    void htmlPage_isMarkedNoCache() {
        given().get("/login")
                .then().statusCode(200)
                .header("Cache-Control", containsString("no-cache"));
    }
}
