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
import static net.zodac.diurnal.http.HttpStatusCodes.CONFLICT;
import static net.zodac.diurnal.http.HttpStatusCodes.FORBIDDEN;
import static net.zodac.diurnal.http.HttpStatusCodes.FOUND;
import static net.zodac.diurnal.http.HttpStatusCodes.MOVED_PERMANENTLY;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AdminWebResourceIT extends IntegrationTestBase {

    @Override
    protected void createDbState() {
        newUser("admin@lt.test", "Admin User", Role.ADMIN.storageValue());
        newUser("user@lt.test", "Regular User", Role.USER.storageValue());
    }

    // ── Authorization ─────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void usersPage_admin_returns200() {
        given().get("/admin/users")
                .then().statusCode(OK)
                .contentType(containsString("text/html"))
                .body(containsString("User Management"));
    }

    @Test
    @TestSecurity(user = "user@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void usersPage_nonAdmin_returns403() {
        given().get("/admin/users")
                .then().statusCode(FORBIDDEN);
    }

    @Test
    @TestSecurity(user = "user@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void usersPage_nonAdmin_403PageIsStyledHtml() {
        given().get("/admin/users")
                .then().statusCode(FORBIDDEN)
                .contentType(containsString("text/html"))
                .body(containsString("Access Denied"));
    }

    @Test
    @TestSecurity(user = "user@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void usersPage_nonAdmin_403PageHasNavbar() {
        // The error page should still render the navbar so users can navigate away
        given().get("/admin/users")
                .then().statusCode(FORBIDDEN)
                .body(containsString("Dashboard"))
                .body(containsString("Log out"));
    }

    @Test
    void usersPage_unauthenticated_redirectsToLogin() {
        given().redirects().follow(false)
                .get("/admin/users")
                .then().statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND)))
                .header("Location", containsString("/login"));
    }

    @Test
    @TestSecurity(user = "user@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void changeRole_nonAdmin_returns403() {
        final UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().formParam("role", "admin")
                .post("/internal/admin/users/" + userId + "/role")
                .then().statusCode(FORBIDDEN);
    }

    // ── User list content ─────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void usersPage_showsBothUsers() {
        given().get("/admin/users")
                .then().statusCode(OK)
                .body(containsString("admin@lt.test"))
                .body(containsString("user@lt.test"));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void usersPage_datesTooltipShowsViewingAdminTimezone() {
        runInTx(() -> {
            final User admin = User.findByEmail("admin@lt.test").orElseThrow();
            admin.timezone = "America/New_York";
            admin.persist();
        });

        given().get("/admin/users")
                .then().statusCode(OK)
                // Assert on the tooltip bubble's TEXT content (>...<), not the aria-label attribute
                // (= "..."), so a literal, un-interpolated {u.zoneLabel} in the bubble would fail here.
                .body(containsString(">Timezone: America/New_York<"))
                // Guard against Qute passing an include param verbatim (the original bug rendered the
                // literal braces in the bubble).
                .body(not(containsString("{u.zoneLabel}")))
                .body(not(containsString("{u.zoneTooltip}")));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void usersPage_datesTooltipFallsBackToServerTimezoneWhenAdminUnset() {
        // The admin seeded by createDbState has no timezone override, so the tooltip names the
        // server-default zone (UTC in the test profile).
        given().get("/admin/users")
                .then().statusCode(OK)
                .body(containsString(">Timezone: UTC<"))
                .body(not(containsString("{u.zoneLabel}")))
                .body(not(containsString("{u.zoneTooltip}")));
    }

    // ── Role change ───────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void changeRole_promoteUser_updatesRole() {
        final UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().formParam("role", "admin")
                .post("/internal/admin/users/" + userId + "/role")
                .then().statusCode(OK);

        runInTx(() -> {
            final User u = User.findByEmail("user@lt.test").orElseThrow();
            assertThat(u.role)
                .as("unexpected value")
                .isEqualTo(Role.ADMIN.storageValue());
        });
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void changeRole_demoteLastAdmin_returns409() {
        final UUID adminId = runInTxReturning(() -> User.findByEmail("admin@lt.test").orElseThrow().id);

        given().formParam("role", "user")
                .post("/internal/admin/users/" + adminId + "/role")
                .then().statusCode(CONFLICT)
                .body(containsString("last administrator"));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void changeRole_demoteAdminWhenAnotherExists_succeeds() {
        // Promote user@lt.test to admin first, then demote admin@lt.test
        runInTx(() -> {
            final User u = User.findByEmail("user@lt.test").orElseThrow();
            u.role = Role.ADMIN.storageValue();
            u.persist();
        });

        final UUID adminId = runInTxReturning(() -> User.findByEmail("admin@lt.test").orElseThrow().id);
        given().formParam("role", "user")
                .post("/internal/admin/users/" + adminId + "/role")
                .then().statusCode(OK);
        runInTx(() -> assertThat(User.findByEmail("admin@lt.test").orElseThrow().role)
            .as("unexpected value")
            .isEqualTo(Role.USER.storageValue()));
    }

    // ── Confirm-delete panel ──────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void confirmDeleteUser_showsConfirmRow() {
        final UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().get("/internal/admin/users/" + userId + "/confirm-delete")
                .then().statusCode(OK)
                .contentType(containsString("text/html"))
                .body(containsString("user@lt.test"))
                .body(containsString("Delete this user, their actions and logs?"))
                .body(containsString("Cancel"));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void confirmDeleteUser_notFound_returns409() {
        given().get("/internal/admin/users/81d92e7a-6589-4050-984d-98234bcece64/confirm-delete")
                .then().statusCode(CONFLICT);
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void userRow_cancelRestoresRow() {
        final UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().get("/internal/admin/users/" + userId)
                .then().statusCode(OK)
                .contentType(containsString("text/html"))
                .body(containsString("user@lt.test"))
                .body(containsString("confirm-delete"));
    }

    // ── Delete ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void deleteUser_regularUser_removesUser() {
        final UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().post("/internal/admin/users/" + userId + "/delete")
                .then().statusCode(OK);

        runInTx(() -> assertThat(User.findByEmail("user@lt.test"))
            .as("expected condition to be false")
            .isNotPresent());
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void deleteUser_lastAdmin_returns409() {
        final UUID adminId = runInTxReturning(() -> User.findByEmail("admin@lt.test").orElseThrow().id);

        given().post("/internal/admin/users/" + adminId + "/delete")
                .then().statusCode(CONFLICT)
                .body(containsString("last administrator"));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {Role.Values.USER_INTERNAL_VALUE, Role.Values.ADMIN_INTERNAL_VALUE})
    void deleteUser_notFound_returns409() {
        given().post("/internal/admin/users/" + UUID.randomUUID() + "/delete")
                .then().statusCode(CONFLICT)
                .body(containsString("not found"));
    }

    // ── Helper ────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface TxSupplier<T> {
        T get();
    }

    private <T> T runInTxReturning(final TxSupplier<T> block) {
        final Object[] result = new Object[1];
        runInTx(() -> result[0] = block.get());
        @SuppressWarnings("unchecked")
        final T t = (T) result[0];
        return t;
    }
}
