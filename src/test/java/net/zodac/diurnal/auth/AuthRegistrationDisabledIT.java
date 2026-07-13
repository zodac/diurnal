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

package net.zodac.diurnal.auth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.web.RegistrationDisabledProfile;
import org.junit.jupiter.api.Test;

/**
 * Verifies the JSON API ({@code POST /api/auth/register}) honours {@code ENABLE_REGISTRATION=false} just like the web form, so the API can never be
 * used to bypass the registration switch. The first-run setup account can still be created (so the switch can never lock out the very first user).
 * Uses {@link RegistrationDisabledProfile} to force {@code registration.enabled=false}.
 */
@QuarkusTest
@TestProfile(RegistrationDisabledProfile.class)
class AuthRegistrationDisabledIT extends IntegrationTestBase {

    @Test
    void register_firstRun_createsInitialAccountDespiteRegistrationDisabled() {
        // No users exist (setUp truncates), so the first account is always permitted.
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"first@example.com","displayName":"First","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then().statusCode(201);
    }

    @Test
    void register_afterUserExists_isForbiddenAndCreatesNoUser() {
        runInTx(() -> newUser("existing@example.com", "Existing"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"second@example.com","displayName":"Second","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then()
                .statusCode(403)
                .body("message", containsStringIgnoringCase("registration is disabled"));

        runInTx(() -> assertThat(User.count())
            .as("No new user should be created via the API when registration is disabled")
            .isEqualTo(1));
    }
}
