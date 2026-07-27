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
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the administrative IP-lockout API ({@code /api/v1/admin/ip-lockouts}) with the per-IP lockout enabled: listing the currently
 * locked IPs and the lockout history (the same data the admin page renders via the shared {@link IpLockoutService}), the manual unlock (which clears
 * the live lockout and stamps the history row), the out-of-range-page rejection and the admin-role requirement.
 */
@QuarkusTest
@TestProfile(IpThrottleProfile.class)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class AdminIpLockoutsApiIT extends IntegrationTestBase {

    private static final int MAX_ATTEMPTS = 5;
    private static final String ADMIN_EMAIL = "iplock-admin@lt.test";
    private static final String LOCKED_IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final String OTHER_IP = "198.51.100.9"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final Instant SESSION_INSTANT = Instant.parse("2026-06-15T00:00:00Z");

    @Inject
    IpThrottle ipThrottle;

    @Inject
    IpLockoutService ipLockoutService;

    @Inject
    SessionStore sessionStore;

    @Inject
    AppClock clock;

    User admin;
    User regularUser;

    @BeforeEach
    void clearThrottle() {
        ipThrottle.clear();
    }

    @Override
    protected void createDbState() {
        IpLockout.deleteAll(); // not one of the base-truncated tables
        admin       = newUser(ADMIN_EMAIL, "IP Lock Admin", Role.ADMIN.storageValue());
        regularUser = newUser("iplock-user@lt.test", "IP Lock User");
    }

    @Test
    void listCurrent_listsTheLockedIpWithItsFailureCount() {
        lockIp(LOCKED_IP);

        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/ip-lockouts")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].ipAddress", equalTo(LOCKED_IP))
                .body("items[0].failureCount", equalTo(MAX_ATTEMPTS));
    }

    @Test
    void listCurrent_emptyWhenNoneLocked() {
        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/ip-lockouts")
                .then().statusCode(200)
                .body("items.size()", equalTo(0));
    }

    @Test
    void listHistory_listsTheActiveLockout() {
        lockIp(LOCKED_IP);

        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/ip-lockouts/history")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].ipAddress", equalTo(LOCKED_IP))
                .body("items[0].status", equalTo("ACTIVE"))
                .body("items[0].failureCount", equalTo(MAX_ATTEMPTS))
                .body("totalCount", equalTo(1))
                .body("currentPage", equalTo(1));
    }

    @Test
    void listHistory_outOfRangePage_isRejected() {
        lockIp(LOCKED_IP);

        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/ip-lockouts/history?page=2")
                .then().statusCode(400);
    }

    @Test
    void unlock_clearsTheLiveLockoutAndStampsHistoryAsUnlocked() {
        lockIp(LOCKED_IP);

        given().header("Authorization", "Bearer " + adminToken())
                .delete("/api/v1/admin/ip-lockouts/" + LOCKED_IP)
                .then().statusCode(204);

        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/ip-lockouts")
                .then().statusCode(200)
                .body("items.size()", equalTo(0));

        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/ip-lockouts/history")
                .then().statusCode(200)
                .body("items[0].status", equalTo("UNLOCKED"))
                .body("items[0].unlockedBy", equalTo(ADMIN_EMAIL));
    }

    @Test
    void unlock_ipThatIsNotLocked_isNotFound() {
        given().header("Authorization", "Bearer " + adminToken())
                .delete("/api/v1/admin/ip-lockouts/" + OTHER_IP)
                .then().statusCode(404);
    }

    @Test
    void listCurrent_nonAdminCaller_isForbidden() {
        final String userToken = sessionStore.create(regularUser, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
        given().header("Authorization", "Bearer " + userToken)
                .get("/api/v1/admin/ip-lockouts")
                .then().statusCode(403);
    }

    private void lockIp(final String ip) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            ipLockoutService.recordFailure(ip, clock.now());
        }
    }

    private String adminToken() {
        return sessionStore.create(admin, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
    }
}
