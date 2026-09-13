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

package net.zodac.diurnal.web.admin;

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.DummyValues.DUMMY_UUID;
import static net.zodac.diurnal.http.HttpStatusCodes.NOT_FOUND;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the administrative IP-lockout HTMX endpoints when the per-IP lockout is <em>disabled</em> (the default test profile). The
 * matching public-API surface is covered by {@code AdminIpLockoutsDisabledApiIT}; this is its internal counterpart, and the two must agree - a
 * console for a subsystem the deployment has switched off is not merely empty, it does not exist.
 */
@QuarkusTest
@TestSecurity(user = AdminIpLockoutsDisabledInternalIT.ADMIN_EMAIL, roles = Role.Values.ADMIN_INTERNAL_VALUE)
class AdminIpLockoutsDisabledInternalIT extends IntegrationTestBase {

    static final String ADMIN_EMAIL = "iplock-disabled-internal@lt.test";

    private static final String SOME_IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - test IP

    @Override
    protected void createDbState() {
        newUser(ADMIN_EMAIL, "IP Lock Disabled Internal Admin", Role.ADMIN.storageValue());
    }

    @Test
    void history_whenDisabled_isNotFound() {
        given().get("/internal/admin/ip-lockouts/history")
                .then().statusCode(NOT_FOUND);
    }

    @Test
    void confirmUnlock_whenDisabled_isNotFound() {
        given().get("/internal/admin/ip-lockouts/" + DUMMY_UUID + "/confirm-unlock")
                .then().statusCode(NOT_FOUND);
    }

    @Test
    void row_whenDisabled_isNotFound() {
        given().get("/internal/admin/ip-lockouts/" + DUMMY_UUID + "/row")
                .then().statusCode(NOT_FOUND);
    }

    @Test
    void unlock_whenDisabled_isNotFound() {
        given().post("/internal/admin/ip-lockouts/" + SOME_IP + "/unlock")
                .then().statusCode(NOT_FOUND);
    }
}
