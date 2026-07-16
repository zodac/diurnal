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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.auth.Session;
import net.zodac.diurnal.auth.SessionStore;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the profile-mutation API ({@code PATCH /api/v1/users/me} and {@code PUT /api/v1/users/me/password}): PATCH semantics,
 * coercion vs rejection per preference (the same rules the Settings page applies via the shared {@code ProfileService} and
 * {@code PasswordChangeService}), and the password change's other-session revocation.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class UserMeApiIT extends IntegrationTestBase {

    private static final Instant SESSION_INSTANT = Instant.parse("2026-06-15T00:00:00Z");
    private static final String PRIMARY = "me-api-it@lt.test";

    @Inject
    SessionStore sessionStore;

    User user;

    @Override
    protected void createDbState() {
        user = newUser(PRIMARY, "Me API User");
    }

    // ── PATCH /api/v1/users/me ────────────────────────────────────────────────

    @Test
    void patchMe_displayNameAndPreferences_updatesAndReturnsProfile() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("""
                        {"displayName":"Renamed User","preferences":{"theme":"dark","pageSize":25,"timezone":"Europe/London"}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(200)
                .body("displayName", equalTo("Renamed User"))
                .body("preferences.theme", equalTo("dark"))
                .body("preferences.pageSize", equalTo(25))
                .body("preferences.timezone", equalTo("Europe/London"));

        runInTx(() -> {
            final User updated = User.findByEmail(PRIMARY).orElseThrow();
            assertThat(updated.displayName)
                .as("the display name should be persisted")
                .isEqualTo("Renamed User");
            assertThat(updated.pageSize)
                .as("the page size should be persisted")
                .isEqualTo(25);
        });
    }

    @Test
    void patchMe_absentFieldsKeepCurrentValues() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"font":"dyslexic"}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(200)
                .body("displayName", equalTo("Me API User"))
                .body("preferences.font", equalTo("dyslexic"))
                .body("preferences.theme", equalTo("system"));
    }

    @Test
    void patchMe_unknownEnumValue_isRejected_matchingTheSettingsPage() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"theme":"neon"}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(400)
                .body("message", containsString("Theme must be one of: system, light, dark"));

        given().header("Authorization", "Bearer " + token())
                .get("/api/v1/users/me")
                .then().statusCode(200)
                .body("preferences.theme", equalTo("system"));
    }

    @Test
    void patchMe_unofferedTimezone_isRejected() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"timezone":"Mars/Phobos"}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(400)
                .body("message", containsString("Timezone must be one of"));
    }

    @Test
    void patchMe_blankTimezone_resetsToServerDefault() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"timezone":"Europe/London"}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(200)
                .body("preferences.timezone", equalTo("Europe/London"));

        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"timezone":""}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(200)
                .body("preferences.timezone", nullValue());
    }

    @Test
    void patchMe_outOfRangePageSize_isRejected() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"pageSize":9999}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(400)
                .body("message", containsString("Items per page"));
    }

    @Test
    void patchMe_overlongDisplayName_isRejected() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"displayName\":\"" + "x".repeat(101) + "\"}")
                .patch("/api/v1/users/me")
                .then().statusCode(400)
                .body("message", containsString("between 2 and 100"));
    }

    @Test
    void patchMe_statsFieldsArrangement_roundTripsThroughGetMe() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"statsFields":[
                            {"key":"total-count","enabled":true},
                            {"key":"current-streak","enabled":false},
                            {"key":"last-performed","enabled":true}
                        ]}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(200)
                .body("preferences.statsFields[0].key", equalTo("total-count"))
                .body("preferences.statsFields[0].enabled", equalTo(true))
                .body("preferences.statsFields[1].key", equalTo("current-streak"))
                .body("preferences.statsFields[1].enabled", equalTo(false));
    }

    @Test
    void patchMe_emptyBody_isNoOp200() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{}")
                .patch("/api/v1/users/me")
                .then().statusCode(200)
                .body("displayName", equalTo("Me API User"));
    }

    // ── PUT /api/v1/users/me/password ─────────────────────────────────────────

    @Test
    void changePassword_revokesOtherSessions_keepsCaller() {
        final String callerToken = token();
        final String otherDeviceToken = token();

        given().header("Authorization", "Bearer " + callerToken)
                .contentType(ContentType.JSON)
                .body("""
                        {"currentPassword":"%s","newPassword":"a brand new passphrase"}
                        """.formatted(TEST_PASSWORD))
                .put("/api/v1/users/me/password")
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + callerToken)
                .get("/api/v1/users/me")
                .then().statusCode(200);
        given().header("Authorization", "Bearer " + otherDeviceToken)
                .get("/api/v1/users/me")
                .then().statusCode(401);
    }

    @Test
    void changePassword_wrongCurrentPassword_returns400() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("""
                        {"currentPassword":"not-the-password","newPassword":"a brand new passphrase"}
                        """)
                .put("/api/v1/users/me/password")
                .then().statusCode(400)
                .body("message", containsString("Current password is incorrect"));
    }

    @Test
    void changePassword_overlongNewPassword_returns400() {
        given().header("Authorization", "Bearer " + token())
                .contentType(ContentType.JSON)
                .body("{\"currentPassword\":\"" + TEST_PASSWORD + "\",\"newPassword\":\"" + "a".repeat(129) + "\"}")
                .put("/api/v1/users/me/password")
                .then().statusCode(400)
                .body("message", containsString("128"));
    }

    @Test
    void changePassword_oidcOnlyAccount_returns403() {
        runInTx(UserMeApiIT::newOidcUser);
        final User oidcUser = User.findByEmail("oidc-me@lt.test").orElseThrow();
        final String oidcToken = sessionStore.create(oidcUser, Session.AUTH_SOURCE_OIDC, null, null, SESSION_INSTANT);

        given().header("Authorization", "Bearer " + oidcToken)
                .contentType(ContentType.JSON)
                .body("""
                        {"currentPassword":"irrelevant","newPassword":"whatever"}
                        """)
                .put("/api/v1/users/me/password")
                .then().statusCode(403);
    }

    private String token() {
        return sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
    }

    // Mirrors what OidcUserProvisioner writes on first login: issuer/subject set, no password hash.
    private static void newOidcUser() {
        final User oidc = new User();
        oidc.email = "oidc-me@lt.test";
        oidc.displayName = "OIDC Me";
        oidc.oidcIssuer = "https://diurnal.example.com/idp";
        oidc.oidcSubject = "subject-oidc-me";
        oidc.role = Role.USER.storageValue();
        oidc.persist();
    }
}
