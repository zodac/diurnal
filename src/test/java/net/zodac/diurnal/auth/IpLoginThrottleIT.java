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
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.Map;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the per-IP throttle in isolation: the account throttle is disabled and the IP limit is small
 * so a single client IP rotating through different accounts trips the IP lock. Runs under its own profile
 * because per-IP state is keyed on the shared loopback address across the whole JVM.
 */
@QuarkusTest
@TestProfile(IpLoginThrottleIT.IpThrottleProfile.class)
class IpLoginThrottleIT extends IntegrationTestBase {

    @Inject
    LoginThrottles loginThrottles;

    @BeforeEach
    void clearThrottle() {
        loginThrottles.clear();
    }

    @Test
    void ipLockout_spansDifferentAccounts_andBlocksAValidLogin() {
        registerUser("victim@example.com", "Victim", "correct_password");

        // Five failures across DIFFERENT accounts from the same (loopback) IP trip the IP lock, even
        // though no single account reaches a limit (the account throttle is off in this profile).
        for (int i = 0; i < 5; i++) {
            postLogin("nobody" + i + "@example.com", "wrong").then().statusCode(401);
        }

        // The IP is now locked, so even the correct password for a real account is refused.
        postLogin("victim@example.com", "correct_password")
                .then()
                .statusCode(429)
                .body("message", containsStringIgnoringCase("too many failed login attempts"));
    }

    private static void registerUser(final String email, final String displayName, final String password) {
        given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"displayName\":\"" + displayName + "\",\"password\":\"" + password + "\"}")
                .post("/api/auth/register")
                .then().statusCode(201);
    }

    private static Response postLogin(final String email, final String password) {
        return given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .post("/api/auth/login");
    }

    /**
     * Turns the IP throttle on with a small limit and turns the account throttle off, so the IP dimension
     * can be exercised on its own.
     */
    public static final class IpThrottleProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "password.auth.throttle.enabled", "false",
                    "password.auth.ip-throttle.enabled", "true",
                    "password.auth.ip-throttle.max-attempts", "5",
                    "password.auth.ip-throttle.lockout-duration", "PT15M");
        }
    }
}
