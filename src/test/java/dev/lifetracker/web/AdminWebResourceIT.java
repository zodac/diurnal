package dev.lifetracker.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.lifetracker.IntegrationTestBase;
import dev.lifetracker.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AdminWebResourceIT extends IntegrationTestBase {

    @Override
    protected void createDbState() {
        newUser("admin@lt.test", "Admin User", User.ROLE_ADMIN);
        newUser("user@lt.test", "Regular User", User.ROLE_USER);
    }

    // ── Authorization ─────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void usersPage_admin_returns200() {
        given().get("/admin/users")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("User Management"));
    }

    @Test
    @TestSecurity(user = "user@lt.test", roles = "user")
    void usersPage_nonAdmin_returns403() {
        given().get("/admin/users")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "user@lt.test", roles = "user")
    void usersPage_nonAdmin_403PageIsStyledHtml() {
        given().get("/admin/users")
                .then().statusCode(403)
                .contentType(containsString("text/html"))
                .body(containsString("Access Denied"));
    }

    @Test
    @TestSecurity(user = "user@lt.test", roles = "user")
    void usersPage_nonAdmin_403PageHasNavbar() {
        // The error page should still render the navbar so users can navigate away
        given().get("/admin/users")
                .then().statusCode(403)
                .body(containsString("Dashboard"))
                .body(containsString("Log out"));
    }

    @Test
    void usersPage_unauthenticated_redirectsToLogin() {
        given().redirects().follow(false)
                .get("/admin/users")
                .then().statusCode(anyOf(equalTo(301), equalTo(302)))
                .header("Location", containsString("/login"));
    }

    @Test
    @TestSecurity(user = "user@lt.test", roles = "user")
    void changeRole_nonAdmin_returns403() {
        UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().formParam("role", "admin")
                .post("/admin/users/" + userId + "/role")
                .then().statusCode(403);
    }

    // ── User list content ─────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void usersPage_showsBothUsers() {
        given().get("/admin/users")
                .then().statusCode(200)
                .body(containsString("admin@lt.test"))
                .body(containsString("user@lt.test"));
    }

    // ── Role change ───────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void changeRole_promoteUser_updatesRole() {
        UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().formParam("role", "admin")
                .post("/admin/users/" + userId + "/role")
                .then().statusCode(200);

        runInTx(() -> {
            User u = User.findByEmail("user@lt.test").orElseThrow();
            assertEquals(User.ROLE_ADMIN, u.role);
        });
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void changeRole_demoteLastAdmin_returns409() {
        UUID adminId = runInTxReturning(() -> User.findByEmail("admin@lt.test").orElseThrow().id);

        given().formParam("role", "user")
                .post("/admin/users/" + adminId + "/role")
                .then().statusCode(409)
                .body(containsString("last administrator"));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void changeRole_demoteAdminWhenAnotherExists_succeeds() {
        // Promote user@lt.test to admin first, then demote admin@lt.test
        UUID _ = runInTxReturning(() -> {
            User u = User.findByEmail("user@lt.test").orElseThrow();
            u.role = User.ROLE_ADMIN;
            u.persist();
            return u.id;
        });

        UUID adminId = runInTxReturning(() -> User.findByEmail("admin@lt.test").orElseThrow().id);
        given().formParam("role", "user")
                .post("/admin/users/" + adminId + "/role")
                .then().statusCode(200);
        runInTx(() -> assertEquals(User.ROLE_USER, User.findByEmail("admin@lt.test").orElseThrow().role));
    }

    // ── Confirm-delete panel ──────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void confirmDeleteUser_showsConfirmRow() {
        UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().get("/admin/users/" + userId + "/confirm-delete")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("user@lt.test"))
                .body(containsString("permanently remove"))
                .body(containsString("Cancel"));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void confirmDeleteUser_notFound_returns409() {
        given().get("/admin/users/" + UUID.randomUUID() + "/confirm-delete")
                .then().statusCode(409);
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void userRow_cancelRestoresRow() {
        UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().get("/admin/users/" + userId)
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("user@lt.test"))
                .body(containsString("confirm-delete"));
    }

    // ── Delete ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void deleteUser_regularUser_removesUser() {
        UUID userId = runInTxReturning(() -> User.findByEmail("user@lt.test").orElseThrow().id);

        given().post("/admin/users/" + userId + "/delete")
                .then().statusCode(200);

        runInTx(() -> assertFalse(User.findByEmail("user@lt.test").isPresent()));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void deleteUser_lastAdmin_returns409() {
        UUID adminId = runInTxReturning(() -> User.findByEmail("admin@lt.test").orElseThrow().id);

        given().post("/admin/users/" + adminId + "/delete")
                .then().statusCode(409)
                .body(containsString("last administrator"));
    }

    @Test
    @TestSecurity(user = "admin@lt.test", roles = {"user", "admin"})
    void deleteUser_notFound_returns409() {
        given().post("/admin/users/" + UUID.randomUUID() + "/delete")
                .then().statusCode(409)
                .body(containsString("not found"));
    }

    // ── Helper ────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface TxSupplier<T> {
        T get() throws Throwable;
    }

    private <T> T runInTxReturning(TxSupplier<T> block) {
        final Object[] result = new Object[1];
        runInTx(() -> result[0] = block.get());
        @SuppressWarnings("unchecked")
        T t = (T) result[0];
        return t;
    }
}
