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

package net.zodac.diurnal.user;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Header;
import jakarta.inject.Inject;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.auth.TokenService;
import org.junit.jupiter.api.Test;

@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class UserResourceIT extends IntegrationTestBase {

    @Inject
    TokenService tokenService;

    User user;

    @Override
    protected void createDbState() {
        user = newUser("me-api@lt.test", "Me User");
    }

    @Test
    void me_withToken_returnsOwnProfile() {
        given().header(bearer())
                .get("/api/users/me")
                .then().statusCode(200)
                .body("id", not(nullValue()))
                .body("email", equalTo("me-api@lt.test"))
                .body("displayName", equalTo("Me User"))
                .body("role", equalTo("user"))
                // Preferences are nested and reflect the new user's entity defaults.
                .body("preferences.theme", equalTo("system"))
                .body("preferences.pageSize", equalTo(5))
                .body("preferences.calendarView", equalTo("full"))
                .body("preferences.timezone", nullValue());
    }

    @Test
    void me_reflectsAdminRoleAndCustomisedPreferences() {
        final User[] holder = new User[1];
        runInTx(() -> {
            final User u = newUser("admin-api@lt.test", "Admin User", User.ROLE_ADMIN);
            u.theme = "dark";
            u.pageSize = 50;
            u.calendarView = "minimal";
            u.timezone = "Europe/London";
            u.persist();
            holder[0] = u;
        });
        final User admin = holder[0];
        assertThat(admin)
            .as("admin user should have been created in the transaction")
            .isNotNull();

        given().header(new Header("Authorization", "Bearer " + tokenService.generateToken(admin)))
                .get("/api/users/me")
                .then().statusCode(200)
                .body("role", equalTo("admin"))
                .body("preferences.theme", equalTo("dark"))
                .body("preferences.pageSize", equalTo(50))
                .body("preferences.calendarView", equalTo("minimal"))
                .body("preferences.timezone", equalTo("Europe/London"));
    }

    @Test
    void me_withBasicCredentials_returns401_basicDisabled() {
        // HTTP Basic is deliberately NOT enabled on /api/* (it would run BCrypt on every request):
        // even valid account credentials sent as Basic are ignored, so the request is anonymous → 401.
        // BCrypt therefore never runs for a Basic header, so this cannot be used to guess passwords.
        given().auth().preemptive().basic("me-api@lt.test", TEST_PASSWORD)
                .get("/api/users/me")
                .then().statusCode(401);
    }

    @Test
    void me_withoutToken_returns401() {
        given()
                .get("/api/users/me")
                .then().statusCode(401);
    }

    private Header bearer() {
        return new Header("Authorization", "Bearer " + tokenService.generateToken(user));
    }
}
