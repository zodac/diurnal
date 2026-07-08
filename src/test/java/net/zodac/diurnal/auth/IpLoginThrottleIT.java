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
import static org.hamcrest.Matchers.containsStringIgnoringCase;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the per-IP throttle in isolation: the account throttle is disabled and the IP limit is small
 * so a single client IP rotating through different accounts trips the IP lock. Runs under its own profile
 * because per-IP state is keyed on the shared loopback address across the whole JVM.
 */
@QuarkusTest
@TestProfile(IpThrottleProfile.class)
class IpLoginThrottleIT extends IntegrationTestBase {

    private static final String DUMMY_EMAIL = "victim@example.com";
    private static final String DUMMY_DISPLAY_NAME = "Victim";
    private static final String DUMMY_PASSWORD = "correct_password";

    @Inject
    LoginThrottles loginThrottles;

    @BeforeEach
    void clearThrottle() {
        loginThrottles.clear();
    }

    @Test
    void ipLockout_spansDifferentAccounts_andBlocksAValidLogin() {
        registerUser();

        // Five failures across DIFFERENT accounts from the same (loopback) IP trip the IP lock, even
        // though no single account reaches a limit (the account throttle is off in this profile).
        for (int i = 0; i < 5; i++) {
            postLogin("nobody" + i + "@example.com", "wrong").then().statusCode(401);
        }

        // The IP is now locked, so even the correct password for a real account is refused.
        postLogin(DUMMY_EMAIL, DUMMY_PASSWORD)
                .then()
                .statusCode(429)
                .body("message", containsStringIgnoringCase("too many failed login attempts"));
    }

    private static void registerUser() {
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + DUMMY_EMAIL + "\",\"displayName\":\"" + DUMMY_DISPLAY_NAME + "\",\"password\":\"" + DUMMY_PASSWORD + "\"}")
                .post("/api/auth/register")
                .then().statusCode(201);
    }

    private static Response postLogin(final String email, final String password) {
        return given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .post("/api/auth/login");
    }
}
