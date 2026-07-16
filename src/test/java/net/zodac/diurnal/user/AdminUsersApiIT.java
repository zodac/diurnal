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

package net.zodac.diurnal.user;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.auth.Session;
import net.zodac.diurnal.auth.SessionStore;
import net.zodac.diurnal.log.ActionLog;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the administrative user-management API ({@code /api/v1/admin/users}): listing (paged by the caller's page-size preference),
 * role changes and deletions with the last-administrator safeguards (the same rules the admin page applies via the shared {@code AdminUserService}),
 * and the admin-role requirement.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class AdminUsersApiIT extends IntegrationTestBase {

    private static final Instant SESSION_INSTANT = Instant.parse("2026-06-15T00:00:00Z");

    @Inject
    SessionStore sessionStore;

    User admin;
    User regularUser;

    @Override
    protected void createDbState() {
        admin       = newUser("admin-api-it@lt.test", "Admin API User", Role.ADMIN.storageValue());
        regularUser = newUser("admin-api-target@lt.test", "Target User");
    }

    @Test
    void list_returnsAccountsOrderedByCreation_pagedByCallersPreference() {
        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/users")
                .then().statusCode(200)
                .body("items.size()", equalTo(2))
                .body("items[0].email", equalTo("admin-api-it@lt.test"))
                .body("items[0].role", equalTo(Role.ADMIN.storageValue()))
                .body("items[1].email", equalTo("admin-api-target@lt.test"))
                .body("totalCount", equalTo(2))
                .body("currentPage", equalTo(1));
    }

    @Test
    void list_nonAdminCaller_isForbidden() {
        final String userToken = sessionStore.create(regularUser, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
        given().header("Authorization", "Bearer " + userToken)
                .get("/api/v1/admin/users")
                .then().statusCode(403);
    }

    @Test
    void fetchUser_returnsAccount() {
        given().header("Authorization", "Bearer " + adminToken())
                .get("/api/v1/admin/users/" + regularUser.id)
                .then().statusCode(200)
                .body("email", equalTo("admin-api-target@lt.test"))
                .body("displayName", equalTo("Target User"))
                .body("role", equalTo(Role.USER.storageValue()));
    }

    @Test
    void changeRole_promotesToAdmin() {
        given().header("Authorization", "Bearer " + adminToken())
                .contentType(ContentType.JSON)
                .body("""
                        {"role":"admin"}
                        """)
                .patch("/api/v1/admin/users/" + regularUser.id)
                .then().statusCode(200)
                .body("role", equalTo(Role.ADMIN.storageValue()));

        runInTx(() -> assertThat(User.findByEmail("admin-api-target@lt.test").orElseThrow().role)
            .as("the promotion should be persisted")
            .isEqualTo(Role.ADMIN.storageValue()));
    }

    @Test
    void changeRole_unknownRole_returns400() {
        given().header("Authorization", "Bearer " + adminToken())
                .contentType(ContentType.JSON)
                .body("""
                        {"role":"superuser"}
                        """)
                .patch("/api/v1/admin/users/" + regularUser.id)
                .then().statusCode(400)
                .body("message", containsString("Invalid role"));
    }

    @Test
    void changeRole_demotingLastAdmin_returns409() {
        given().header("Authorization", "Bearer " + adminToken())
                .contentType(ContentType.JSON)
                .body("""
                        {"role":"user"}
                        """)
                .patch("/api/v1/admin/users/" + admin.id)
                .then().statusCode(409)
                .body("message", containsString("last administrator"));

        runInTx(() -> assertThat(User.findByEmail("admin-api-it@lt.test").orElseThrow().role)
            .as("the last administrator must keep their role")
            .isEqualTo(Role.ADMIN.storageValue()));
    }

    @Test
    void deleteUser_cascadesActionsAndLogs() {
        final Action[] holder = new Action[1];
        runInTx(() -> {
            holder[0] = newAction(regularUser.id, "Doomed Action");
            newLog(regularUser.id, holder[0].id, LocalDate.of(2026, 6, 15), 3);
        });

        given().header("Authorization", "Bearer " + adminToken())
                .delete("/api/v1/admin/users/" + regularUser.id)
                .then().statusCode(204);

        runInTx(() -> {
            assertThat(User.findByEmail("admin-api-target@lt.test"))
                .as("the account should be hard-deleted")
                .isEmpty();
            assertThat(Action.count("userId", regularUser.id))
                .as("the account's actions should be cascade-deleted")
                .isZero();
            assertThat(ActionLog.count("userId", regularUser.id))
                .as("the account's logs should be cascade-deleted")
                .isZero();
        });
    }

    @Test
    void deleteUser_lastAdmin_returns409() {
        given().header("Authorization", "Bearer " + adminToken())
                .delete("/api/v1/admin/users/" + admin.id)
                .then().statusCode(409)
                .body("message", containsString("last administrator"));
    }

    private String adminToken() {
        return sessionStore.create(admin, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
    }
}
