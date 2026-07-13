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
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Verifies the OIDC auto-redirect: with OIDC enabled and {@code oidc.auto.redirect=true}, a plain {@code GET /login} short-circuits to the OIDC
 * trigger ({@code /oidc-login}) instead of rendering the login form — but still renders the page when there is an error or success message to show.
 * Uses {@link OidcAutoRedirectProfile}.
 */
@QuarkusTest
@TestProfile(OidcAutoRedirectProfile.class)
class OidcAutoRedirectIT extends IntegrationTestBase {

    @Override
    protected void createDbState() {
        // A user must exist so setupRequired() is false; otherwise /login redirects to the first-run
        // /welcome page (local setup) before the OIDC auto-redirect branch is reached.
        newUser("oidc-existing@lt.test", "Existing");
    }

    @Test
    void loginPage_oidcAutoRedirectEnabled_redirectsToOidcTrigger() {
        given().redirects().follow(false)
                .get("/login")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/oidc-login"));
    }

    @Test
    void loginPage_withOidcError_doesNotAutoRedirect_rendersPage() {
        // A failed OIDC attempt must NOT bounce the user straight back into the flow — the error
        // banner has to be shown, so auto-redirect is suppressed when ?error is present.
        given().redirects().follow(false)
                .get("/login?error=oidc")
                .then()
                .statusCode(200)
                .body(containsString("not authorized"));
    }

    @Test
    void loginPage_afterRegistration_doesNotAutoRedirect_rendersPage() {
        // The post-registration success banner must be visible, so ?registered also suppresses the
        // auto-redirect.
        given().redirects().follow(false)
                .queryParam("registered", "true")
                .get("/login")
                .then()
                .statusCode(200)
                .body(containsString("Account created"));
    }
}
