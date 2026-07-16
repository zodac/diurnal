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

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@code POST /api/v1/auth/revoke} — the API twin of the Settings page's "Log out from everywhere". Both surfaces call the one
 * {@code SessionStore.revokeAllForUser}, and both directions are asserted here: the API revoke kills web (cookie) sessions, and the web revoke-all
 * kills API (Bearer) tokens — because web and API sessions are rows in the same store.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class AuthRevokeIT extends IntegrationTestBase {

    private static final Instant SESSION_INSTANT = Instant.parse("2026-06-15T00:00:00Z");

    @Inject
    SessionStore sessionStore;

    User user;
    User otherUser;

    @Override
    protected void createDbState() {
        user      = newUser("revoke-it@lt.test", "Revoke User");
        otherUser = newUser("revoke-other@lt.test", "Other User");
    }

    @Test
    void revoke_killsEverySessionForTheUser_includingTheCaller() {
        final String callerToken = token(user);
        final String otherDeviceToken = token(user);
        final String otherUsersToken = token(otherUser);

        given().header("Authorization", "Bearer " + callerToken)
                .post("/api/v1/auth/revoke")
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + callerToken)
                .get("/api/v1/users/me")
                .then().statusCode(401);
        given().header("Authorization", "Bearer " + otherDeviceToken)
                .get("/api/v1/users/me")
                .then().statusCode(401);
        given().header("Authorization", "Bearer " + otherUsersToken)
                .get("/api/v1/users/me")
                .then().statusCode(200);
    }

    @Test
    void revoke_viaApi_alsoKillsWebCookieSessions() {
        // Web and API sessions are the same store: a "web" session is just a token delivered as the
        // diurnal_session cookie, which SessionAuthMechanism accepts on any route.
        final String webSessionToken = token(user);
        final String callerToken = token(user);

        given().header("Authorization", "Bearer " + callerToken)
                .post("/api/v1/auth/revoke")
                .then().statusCode(204);

        given().cookie("diurnal_session", webSessionToken)
                .get("/api/v1/users/me")
                .then().statusCode(401);
    }

    @Test
    void webRevokeAll_alsoKillsApiBearerTokens() {
        final String webSessionToken = token(user);
        final String apiToken = token(user);

        // The UI-driven flow: the Settings page posts the session cookie to the internal endpoint,
        // which responds with a redirect to /login after clearing every session row.
        given().redirects().follow(false)
                .cookie("diurnal_session", webSessionToken)
                .post("/internal/settings/sessions/revoke-all")
                .then().statusCode(303);

        given().header("Authorization", "Bearer " + apiToken)
                .get("/api/v1/users/me")
                .then().statusCode(401);
    }

    @Test
    void bodylessAuthPosts_acceptAnyContentType() {
        // /logout and /revoke take no body, so a stray Content-Type must not be rejected with a 415
        // (both override the class-level JSON @Consumes with a wildcard).
        given().header("Authorization", "Bearer " + token(user))
                .contentType("text/plain")
                .post("/api/v1/auth/logout")
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + token(user))
                .contentType("text/plain")
                .post("/api/v1/auth/revoke")
                .then().statusCode(204);
    }

    @Test
    void revoke_anonymous_isChallengedWith401() {
        given().post("/api/v1/auth/revoke")
                .then().statusCode(401);
    }

    private String token(final User tokenUser) {
        return sessionStore.create(tokenUser, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
    }
}
