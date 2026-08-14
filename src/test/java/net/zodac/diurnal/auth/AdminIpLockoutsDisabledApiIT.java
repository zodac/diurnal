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
import static net.zodac.diurnal.http.HttpStatusCodes.NOT_FOUND;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the administrative IP-lockout API when the per-IP lockout is <em>disabled</em> (the default test profile). With no lockout
 * state to administer, every operation answers {@code 404} to an authenticated administrator, exactly as registration does when password auth is off.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class AdminIpLockoutsDisabledApiIT extends IntegrationTestBase {

    private static final String ADMIN_EMAIL = "iplock-disabled-admin@lt.test";
    private static final String SOME_IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final Instant SESSION_INSTANT = Instant.parse("2026-06-15T00:00:00Z");

    @Inject
    SessionStore sessionStore;

    private User admin;

    @Override
    protected void createDbState() {
        admin = newUser(ADMIN_EMAIL, "IP Lock Disabled Admin", Role.ADMIN.storageValue());
    }

    @Test
    void listCurrent_whenDisabled_isNotFound() {
        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/ip-lockouts")
                .then().statusCode(NOT_FOUND);
    }

    @Test
    void listHistory_whenDisabled_isNotFound() {
        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/ip-lockouts/history")
                .then().statusCode(NOT_FOUND);
    }

    @Test
    void unlock_whenDisabled_isNotFound() {
        given().header("Authorization", "Bearer " + adminToken())
                .delete("/api/v1/admin/ip-lockouts/" + SOME_IP)
                .then().statusCode(NOT_FOUND);
    }

    private String adminToken() {
        return sessionStore.create(admin, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
    }
}
