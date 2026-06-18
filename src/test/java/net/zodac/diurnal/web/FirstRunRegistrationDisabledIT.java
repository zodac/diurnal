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
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@code ENABLE_REGISTRATION=false} can never lock out the first user: the registration
 * page stays open during first-run setup (no users), but is rejected with {@code 404} once any user
 * exists. Uses {@link RegistrationDisabledProfile} to force {@code registration.enabled=false}.
 */
@QuarkusTest
@TestProfile(RegistrationDisabledProfile.class)
class FirstRunRegistrationDisabledIT extends IntegrationTestBase {

    @Test
    void registerPage_firstRun_availableDespiteRegistrationDisabled() {
        given().get("/register")
                .then()
                .statusCode(200)
                .body(containsString("Create the administrator account"));
    }

    @Test
    void loginPage_firstRun_redirectsToWelcomeDespiteRegistrationDisabled() {
        given().redirects().follow(false)
                .get("/login")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/welcome"));
    }

    @Test
    void register_firstRun_createsInitialAccountDespiteRegistrationDisabled() {
        given().redirects().follow(false)
                .formParam("email", "first@example.com")
                .formParam("displayName", "First Admin")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/login?registered"));
    }

    @Test
    void registerPage_afterUserExists_showsDisabledBannerAndHidesForm() {
        runInTx(() -> newUser("existing@example.com", "Existing"));

        given().get("/register")
                .then()
                .statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Registration is currently disabled. Please contact your system administrator."))
                // The form is replaced by the banner: no password field, but a route back to sign in.
                .body(not(containsString("name=\"password\"")))
                .body(containsString("Back to sign in"));
    }

    @Test
    void registerPost_afterUserExists_isForbiddenAndCreatesNoUser() {
        runInTx(() -> newUser("existing@example.com", "Existing"));

        given().redirects().follow(false)
                .formParam("email", "second@example.com")
                .formParam("displayName", "Second")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(403)
                .body(containsString("Registration is currently disabled. Please contact your system administrator."));

        runInTx(() -> assertEquals(1, User.count(), "No new user should be created when registration is disabled"));
    }
}
