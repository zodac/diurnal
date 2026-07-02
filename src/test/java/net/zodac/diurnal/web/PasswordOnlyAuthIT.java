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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Verifies the login page in a password-only configuration (password auth enabled, OIDC disabled):
 * the local email/password form is rendered and no OIDC "Log in with …" button/link is offered.
 * Uses {@link PasswordOnlyAuthProfile} so the result does not depend on ambient {@code OIDC_ENABLED}.
 */
@QuarkusTest
@TestProfile(PasswordOnlyAuthProfile.class)
class PasswordOnlyAuthIT extends IntegrationTestBase {

    @Override
    protected void createDbState() {
        // A user must exist so setupRequired() is false; otherwise /login redirects to the first-run
        // /welcome page before the login form is rendered.
        newUser("pw-only@lt.test", "Password User");
    }

    @Test
    void loginPage_passwordOnly_showsFormAndNoOidcButton() {
        given().get("/login")
                .then().statusCode(200)
                .body(allOf(
                        containsString("name=\"email\""),
                        containsString("name=\"password\""),
                        containsString("Sign in"),
                        not(containsString("/oidc-login")),
                        not(containsString("Log in with"))));
    }
}
