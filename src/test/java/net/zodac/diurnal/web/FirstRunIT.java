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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Covers the first-run setup flow that activates when the database has no users. The base class
 * truncates all tables before each test and this class adds no seed data, so every test starts in
 * the "no users yet" state.
 */
@QuarkusTest
class FirstRunIT extends IntegrationTestBase {

    @Test
    void loginPage_firstRun_redirectsToWelcome() {
        given().redirects().follow(false)
                .get("/login")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/welcome"));
    }

    @Test
    void welcomePage_firstRun_returnsSetupLanding() {
        given().get("/welcome")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Create administrator account"));
    }

    @Test
    void registerPage_firstRun_showsAdministratorCopy() {
        given().get("/register")
                .then()
                .statusCode(200)
                .body(containsString("Create the administrator account"));
    }

    @Test
    void register_firstRun_createsAdminAndRedirectsToLogin() {
        given().redirects().follow(false)
                .formParam("email", "first@example.com")
                .formParam("displayName", "First Admin")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/login?registered"));

        runInTx(() -> {
            final User created = User.findByEmail("first@example.com").orElseThrow();
            assertThat(created.role)
                .as("First registered user must be an administrator")
                .isEqualTo(User.ROLE_ADMIN);
        });
    }

    @Test
    void unknownPath_firstRunBrowser_redirectsThroughToWelcome() {
        // An unknown browser route during first-run setup ultimately lands on /welcome
        // (unknown route -> /login -> /welcome). RestAssured follows the redirect chain.
        given().header("Accept", "text/html")
                .get("/this-path-does-not-exist")
                .then()
                .statusCode(200)
                .body(containsString("Create administrator account"));
    }

    @Test
    void welcomePage_afterUserExists_redirectsToLogin() {
        runInTx(() -> newUser("existing@example.com", "Existing"));

        given().redirects().follow(false)
                .get("/welcome")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/login"));
    }

    @Test
    void loginPage_afterUserExists_returnsLoginForm() {
        runInTx(() -> newUser("existing@example.com", "Existing"));

        given().get("/login")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Sign in"));
    }
}
