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
import static net.zodac.diurnal.http.HttpStatusCodes.CONFLICT;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.auth.AttemptThrottle;
import net.zodac.diurnal.auth.IpLockout;
import net.zodac.diurnal.auth.IpLockoutService;
import net.zodac.diurnal.auth.IpThrottle;
import net.zodac.diurnal.auth.IpThrottleProfile;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the admin IP-lockout web surface with the lockout enabled: the {@code /admin/users} page renders the single lockout table
 * under User Management (and omits the whole section when there are no lockouts), the {@code /internal/admin/ip-lockouts} HTMX endpoints serve the
 * table partial and perform the manual unlock, and an unlock of an IP that is no longer locked returns the conflict banner. Also confirms these
 * templates render without a Qute error.
 */
@QuarkusTest
@TestProfile(IpThrottleProfile.class)
@TestSecurity(user = AdminIpLockoutsInternalIT.ADMIN_EMAIL, roles = Role.Values.ADMIN_INTERNAL_VALUE)
class AdminIpLockoutsInternalIT extends IntegrationTestBase {

    static final String ADMIN_EMAIL = "iplock-web-admin@lt.test";

    private static final int MAX_ATTEMPTS = 5;
    private static final String LOCKED_IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - test IP
    private static final String OTHER_IP = "198.51.100.9"; // NOPMD: AvoidUsingHardCodedIP - test IP

    @Inject
    IpThrottle ipThrottle;

    @Inject
    IpLockoutService ipLockoutService;

    @Inject
    AppClock clock;

    // IpThrottle is @ApplicationScoped, so its in-memory state survives across tests. The package-private
    // clear() hook is not visible from this package, so reset via the public unlock of every live lockout.
    @BeforeEach
    void clearThrottle() {
        final Instant now = clock.now();
        for (final AttemptThrottle.ActiveLockout lockout : ipThrottle.currentLockouts(now)) {
            ipThrottle.unlock(lockout.key(), now);
        }
    }

    @Override
    protected void createDbState() {
        IpLockout.deleteAll(); // not one of the base-truncated tables
        newUser(ADMIN_EMAIL, "IP Lock Web Admin", Role.ADMIN.storageValue());
    }

    @Test
    void adminPage_rendersTheLockoutSectionWithTheLockedIp() {
        lockIp(LOCKED_IP);

        given().get("/admin/users")
                .then().statusCode(OK)
                .body(containsString("IP Lockouts"))
                .body(containsString(LOCKED_IP))
                .body(containsString("Active"));
    }

    @Test
    void historyPartial_rendersTheLockoutRow() {
        lockIp(LOCKED_IP);

        given().get("/internal/admin/ip-lockouts/history")
                .then().statusCode(OK)
                .body(containsString(LOCKED_IP))
                .body(containsString("Active"));
    }

    @Test
    void confirmUnlock_turnsTheRowIntoTheConfirmRow() {
        lockIp(LOCKED_IP);
        final UUID id = lockoutIdFor(LOCKED_IP);

        given().get("/internal/admin/ip-lockouts/" + id + "/confirm-unlock")
                .then().statusCode(OK)
                .body(containsString(LOCKED_IP))
                .body(containsString("Unlock"))
                .body(containsString("Cancel"));
    }

    @Test
    void row_restoresThePlainActiveRow() {
        lockIp(LOCKED_IP);
        final UUID id = lockoutIdFor(LOCKED_IP);

        given().get("/internal/admin/ip-lockouts/" + id + "/row")
                .then().statusCode(OK)
                .body(containsString(LOCKED_IP))
                .body(containsString("Active"))
                .body(containsString("Unlock"));
    }

    @Test
    void unlock_reRendersTheTableWithTheIpCleared() {
        lockIp(LOCKED_IP);

        given().post("/internal/admin/ip-lockouts/" + LOCKED_IP + "/unlock")
                .then().statusCode(OK)
                // the row is still in the table, now stamped with the Unlocked status. The acting admin's
                // identity is not shown in the row (it lives in the log and the /api/v1 DTO for traceability).
                .body(containsString("Unlocked"));
    }

    @Test
    void unlock_ipNoLongerLocked_returnsConflictBanner() {
        given().post("/internal/admin/ip-lockouts/" + OTHER_IP + "/unlock")
                .then().statusCode(CONFLICT)
                .body(containsString("no longer locked out"));
    }

    @Test
    void adminPage_withNoLockouts_omitsTheSectionEntirely() {
        given().get("/admin/users")
                .then().statusCode(OK)
                .body(not(containsString("IP Lockouts")))
                .body(not(containsString(LOCKED_IP)));
    }

    private void lockIp(final String ip) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            ipLockoutService.recordFailure(ip, clock.now());
        }
    }

    private UUID lockoutIdFor(final String ip) {
        final UUID[] holder = new UUID[1];
        runInTx(() -> holder[0] = IpLockout.<IpLockout>find("ipAddress", ip).firstResult().id);
        return holder[0];
    }
}
