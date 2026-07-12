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
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the single global per-IP lockout in isolation (small limit, its own profile). One shared
 * counter tallies both failed logins and failed registrations from the loopback IP, and once tripped it
 * blocks BOTH surfaces. There is no per-account dimension, and a success never resets the counter.
 */
@QuarkusTest
@TestProfile(IpThrottleProfile.class)
class IpThrottleIT extends IntegrationTestBase {

    private static final int MAX_ATTEMPTS = 5;
    private static final String SEED_EMAIL = "seed@example.com";
    private static final String SEED_PASSWORD = "correct_password";
    private static final Instant FROZEN_NOW = FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).toInstant();

    @Inject
    IpThrottle ipThrottle;

    // IpThrottle is @ApplicationScoped, so its in-memory state survives across tests — wipe it.
    @BeforeEach
    void clearThrottle() {
        ipThrottle.clear();
    }

    @Test
    void failedLogins_lockIp_andBlockBothLoginAndRegistration() {
        registerSeedUser();

        // Five failed logins from this IP trip the lock (each an ordinary 401; the lock is revealed next).
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            postLogin(SEED_EMAIL, "wrong_password").then().statusCode(401);
        }

        // The IP is now locked: even the correct password is refused, with the neutral lockout message.
        postLogin(SEED_EMAIL, SEED_PASSWORD)
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("message", containsStringIgnoringCase("too many failed attempts"));

        // ...and the SAME lock blocks registration, with the SAME neutral message (not "registration").
        postApiRegister("brand-new@example.com")
                .then()
                .statusCode(429)
                .body("message", containsStringIgnoringCase("too many failed attempts"));
    }

    @Test
    void loginAndRegistrationFailures_shareOneCounter() {
        registerSeedUser();

        // Three failed logins plus two rejected (duplicate-email) registrations = five failures on the
        // ONE shared IP counter — no single surface reaches five on its own.
        for (int i = 0; i < 3; i++) {
            postLogin(SEED_EMAIL, "wrong_password").then().statusCode(401);
        }
        for (int i = 0; i < 2; i++) {
            postApiRegister(SEED_EMAIL).then().statusCode(409);
        }

        // The combined tally has tripped the lock: the next attempt on either surface is blocked.
        postLogin(SEED_EMAIL, SEED_PASSWORD).then().statusCode(429);
        postApiRegister("another-new@example.com").then().statusCode(429);
    }

    @Test
    void successfulLogin_doesNotResetTheIpCounter() {
        registerSeedUser();

        // Four failures (one below the limit), then a SUCCESSFUL login — which must not launder the IP's
        // budget — then one more failure tips it over to a lockout.
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            postLogin(SEED_EMAIL, "wrong_password").then().statusCode(401);
        }
        postLogin(SEED_EMAIL, SEED_PASSWORD).then().statusCode(200);
        postLogin(SEED_EMAIL, "wrong_password").then().statusCode(401);

        // The success did not reset the counter, so that fifth failure locked the IP.
        postLogin(SEED_EMAIL, SEED_PASSWORD).then().statusCode(429);
    }

    @Test
    void formLogin_whileIpLocked_setsLockoutCookie() {
        registerSeedUser();

        // Failed web-form logins feed the same shared IP counter (via WebResource.doLogin), so five of
        // them lock the IP; each is a plain error redirect with no lockout cookie yet.
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            postFormLoginWrongPassword().then().statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)));
        }

        // The next form attempt is blocked: doLogin drops the short-lived lockout cookie the login page
        // reads to show its countdown banner.
        postFormLoginWrongPassword()
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .cookie("diurnal_login_lockout", not(emptyOrNullString()));
    }

    @Test
    void formRegister_whileIpLocked_returns429WithExactSecondsHeaderAndBanner() {
        registerSeedUser();

        // Lock the IP via failed logins, then hit the web registration form.
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            postLogin(SEED_EMAIL, "wrong_password").then().statusCode(401);
        }

        // The form register 429 carries the exact seconds left in the shared X-Lockout-Retry-After header
        // (app.js runs a live countdown from it) and renders the no-JS banner stating the exact seconds.
        postFormRegister()
                .then()
                .statusCode(429)
                .header("X-Lockout-Retry-After", notNullValue())
                .body(containsStringIgnoringCase("too many failed attempts"))
                .body(containsStringIgnoringCase("seconds"));
    }

    @Test
    void ipLockout_liftsAfterTheWindowElapses() {
        registerSeedUser();

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            postLogin(SEED_EMAIL, "wrong_password").then().statusCode(401);
        }
        postLogin(SEED_EMAIL, SEED_PASSWORD).then().statusCode(429);

        // The profile's lockout window is 15 minutes; advance past it and the IP works again.
        freezeInstant(FROZEN_NOW.plus(Duration.ofMinutes(16)), ZoneId.of("UTC"));

        postLogin(SEED_EMAIL, SEED_PASSWORD).then().statusCode(200);
    }

    // A logged-in user changing their OWN password gets UNLIMITED tries: the settings password-change flow
    // is wholly separate from this per-IP login/registration lockout. Proven with the throttle turned ON
    // (this profile) at a low limit: failed current-password checks are never gated by the lockout (never
    // 429) and never feed its shared counter, so they can neither lock the IP nor be locked by it.
    @Test
    @TestSecurity(user = SEED_EMAIL, roles = "user")
    void passwordChangeVerifyFailures_areNeitherGatedByNorFeedTheIpLockout() {
        registerSeedUser();

        // Fail the current-password check well past MAX_ATTEMPTS from this IP: every one is a plain 422,
        // never a 429 — the endpoint applies no lockout of its own, however many times it is wrong.
        for (int i = 0; i < MAX_ATTEMPTS * 2; i++) {
            given().formParam("currentPassword", "wrong_password")
                    .post("/settings/password/verify")
                    .then().statusCode(422);
        }

        // The correct current password still verifies (204): those failures caused no self-inflicted lock.
        given().formParam("currentPassword", SEED_PASSWORD)
                .post("/settings/password/verify")
                .then().statusCode(204);

        // ...and none of them touched the shared IP counter: a fresh wrong login is an ordinary 401. It
        // would be an immediate 429 if the (well past MAX_ATTEMPTS) verify failures had fed the lockout.
        postLogin(SEED_EMAIL, "wrong_password").then().statusCode(401);
    }

    private static void registerSeedUser() {
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + SEED_EMAIL + "\",\"displayName\":\"Seed\",\"password\":\"" + SEED_PASSWORD + "\"}")
                .post("/api/auth/register")
                .then().statusCode(201);
    }

    private static Response postLogin(final String email, final String password) {
        return given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .post("/api/auth/login");
    }

    private static Response postApiRegister(final String email) {
        return given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"displayName\":\"Dup\",\"password\":\"password1\"}")
                .post("/api/auth/register");
    }

    private static Response postFormRegister() {
        return given().redirects().follow(false)
                .formParam("email", "form-new@example.com")
                .formParam("displayName", "Form New")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register");
    }

    private static Response postFormLoginWrongPassword() {
        return given().redirects().follow(false)
                .formParam("email", SEED_EMAIL)
                .formParam("password", "wrong_password")
                .post("/login");
    }
}
