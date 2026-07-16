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

package net.zodac.diurnal.action;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the public actions API ({@code /api/v1/actions}): CRUD behaviour, validation errors, ownership isolation, and the
 * cascade-delete of an action's logs.
 */
@QuarkusTest
@TestSecurity(user = "actions-api-it@lt.test", roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class ActionsApiResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "actions-api-it@lt.test";
    static final String OTHER   = "actions-api-other@lt.test";

    UUID primaryId;
    UUID otherId;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Actions API User").id;
        otherId   = newUser(OTHER,   "Other User").id;
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Test
    void list_noActions_returnsEmptyPage() {
        given().get("/api/v1/actions")
                .then().statusCode(200)
                .body("items.size()", equalTo(0))
                .body("totalCount", equalTo(0))
                .body("currentPage", equalTo(1));
    }

    @Test
    void list_returnsOwnActionsSortedByName_excludesOtherUsers() {
        runInTx(() -> {
            newAction(primaryId, "Zumba");
            newAction(primaryId, "Aerobics");
            newAction(otherId,   "Cycling");
        });

        given().get("/api/v1/actions")
                .then().statusCode(200)
                .body("items.size()", equalTo(2))
                .body("items[0].name", equalTo("Aerobics"))
                .body("items[1].name", equalTo("Zumba"))
                .body("totalCount", equalTo(2))
                .body("totalPages", equalTo(1));
    }

    @Test
    void list_paginatesByTheUsersPageSizePreference() {
        // The seeded user's page size is the default (5); seven actions must span two pages, mirroring
        // the Actions page's own pagination.
        runInTx(() -> {
            for (int i = 1; i <= 7; i++) {
                newAction(primaryId, "Action-" + i);
            }
        });

        given().queryParam("page", 2).get("/api/v1/actions")
                .then().statusCode(200)
                .body("items.size()", equalTo(2))
                .body("totalCount", equalTo(7))
                .body("totalPages", equalTo(2))
                .body("currentPage", equalTo(2));
    }

    @Test
    void list_pageBeyondTotal_isRejected() {
        runInTx(() -> {
            for (int i = 1; i <= 7; i++) {
                newAction(primaryId, "Action-" + i);
            }
        });

        // The web UI clamps an out-of-range page to the last page; the API rejects it rather than silently
        // returning a different page than the one requested.
        given().queryParam("page", 99).get("/api/v1/actions")
                .then().statusCode(400)
                .body("message", containsString("99"));
    }

    @Test
    void list_pageBelowOne_isRejected() {
        given().queryParam("page", 0).get("/api/v1/actions")
                .then().statusCode(400);
    }

    @Test
    void list_searchFiltersByName_caseInsensitively() {
        runInTx(() -> {
            newAction(primaryId, "Morning Run");
            newAction(primaryId, "Evening Walk");
        });

        given().queryParam("q", "MORNING").get("/api/v1/actions")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].name", equalTo("Morning Run"));
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Test
    void create_valid_returns201WithDto() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Morning run","colour":"#ff5500"}
                        """)
                .post("/api/v1/actions")
                .then().statusCode(201)
                .body("name", equalTo("Morning run"))
                .body("colour", equalTo("#ff5500"));

        runInTx(() -> assertThat(Action.count("userId = ?1 and name = ?2", primaryId, "Morning run"))
            .as("the created action should be persisted for the caller")
            .isEqualTo(1));
    }

    @Test
    void create_nameIsStripped() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"  Padded  "}
                        """)
                .post("/api/v1/actions")
                .then().statusCode(201)
                .body("name", equalTo("Padded"));
    }

    @Test
    void create_invalidColour_isRejected() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Bad Colour","colour":"not-a-colour"}
                        """)
                .post("/api/v1/actions")
                .then().statusCode(400)
                .body("message", containsString("colour"));
    }

    @Test
    void create_missingName_returns400() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .post("/api/v1/actions")
                .then().statusCode(400)
                .body("message", containsString("cannot be empty"));
    }

    @Test
    void create_blankName_returns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"   "}
                        """)
                .post("/api/v1/actions")
                .then().statusCode(400)
                .body("message", containsString("cannot be empty"));
    }

    @Test
    void create_overlongName_returns400() {
        final String longName = "x".repeat(101);
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + longName + "\"}")
                .post("/api/v1/actions")
                .then().statusCode(400)
                .body("message", containsString("longer than 100"));
    }

    @Test
    void create_duplicateName_returns409() {
        runInTx(() -> newAction(primaryId, "Running"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Running"}
                        """)
                .post("/api/v1/actions")
                .then().statusCode(409)
                .body("message", containsString("already exists"));
    }

    // ── Get ───────────────────────────────────────────────────────────────────

    @Test
    void get_ownAction_returnsDto() {
        final Action action = newActionInTx(primaryId, "Running");

        given().get("/api/v1/actions/" + action.id)
                .then().statusCode(200)
                .body("id", equalTo(action.id.toString()))
                .body("name", equalTo("Running"));
    }

    @Test
    void get_otherUsersAction_returns404() {
        final Action action = newActionInTx(otherId, "Cycling");

        given().get("/api/v1/actions/" + action.id)
                .then().statusCode(404);
    }

    @Test
    void get_unknownAction_returns404() {
        given().get("/api/v1/actions/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Test
    void update_renameAndRecolour_returnsUpdatedDto() {
        final Action action = newActionInTx(primaryId, "Running");

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Trail running","colour":"#00aa11"}
                        """)
                .patch("/api/v1/actions/" + action.id)
                .then().statusCode(200)
                .body("name", equalTo("Trail running"))
                .body("colour", equalTo("#00aa11"));
    }

    @Test
    void update_absentFieldsKeepCurrentValues() {
        final Action action = newActionInTx(primaryId, "Running");

        given().contentType(ContentType.JSON)
                .body("""
                        {"colour":"#00aa11"}
                        """)
                .patch("/api/v1/actions/" + action.id)
                .then().statusCode(200)
                .body("name", equalTo("Running"))
                .body("colour", equalTo("#00aa11"));
    }

    @Test
    void update_blankName_returns400() {
        final Action action = newActionInTx(primaryId, "Running");

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":" "}
                        """)
                .patch("/api/v1/actions/" + action.id)
                .then().statusCode(400);
    }

    @Test
    void update_duplicateName_returns409() {
        final Action action = newActionInTx(primaryId, "Running");
        runInTx(() -> newAction(primaryId, "Cycling"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Cycling"}
                        """)
                .patch("/api/v1/actions/" + action.id)
                .then().statusCode(409);
    }

    @Test
    void update_renameToOwnName_isAllowed() {
        final Action action = newActionInTx(primaryId, "Running");

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Running"}
                        """)
                .patch("/api/v1/actions/" + action.id)
                .then().statusCode(200)
                .body("name", equalTo("Running"));
    }

    @Test
    void update_otherUsersAction_returns404() {
        final Action action = newActionInTx(otherId, "Cycling");

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Hijacked"}
                        """)
                .patch("/api/v1/actions/" + action.id)
                .then().statusCode(404);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removesActionAndItsLogs() {
        final Action action = newActionInTx(primaryId, "Running");
        runInTx(() -> newLog(primaryId, action.id, FIXED_TODAY, 3));

        given().delete("/api/v1/actions/" + action.id)
                .then().statusCode(204);

        runInTx(() -> {
            assertThat(Action.<Action>findById(action.id))
                .as("the action should be hard-deleted")
                .isNull();
            assertThat(ActionLog.count("actionId", action.id))
                .as("the action's logs should be cascade-deleted")
                .isZero();
        });
    }

    @Test
    void delete_otherUsersAction_returns404() {
        final Action action = newActionInTx(otherId, "Cycling");

        given().delete("/api/v1/actions/" + action.id)
                .then().statusCode(404);

        runInTx(() -> assertThat(Action.<Action>findById(action.id))
            .as("the other user's action must be untouched")
            .isNotNull());
    }

    @Test
    void delete_unknownAction_returns404() {
        given().delete("/api/v1/actions/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    private Action newActionInTx(final UUID userId, final String name) {
        final Action[] holder = new Action[1];
        runInTx(() -> holder[0] = newAction(userId, name));
        return holder[0];
    }
}
