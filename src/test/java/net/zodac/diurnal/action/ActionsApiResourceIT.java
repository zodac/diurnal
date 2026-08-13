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
import java.util.List;
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
@TestSecurity(user = "actions-api-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class ActionsApiResourceIT extends IntegrationTestBase {

    private static final String PRIMARY = "actions-api-it@lt.test";
    private static final String OTHER   = "actions-api-other@lt.test";

    private UUID primaryId;
    private UUID otherId;

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
            .isEqualTo(1L));
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
    void create_absentColour_takesASuggestedColour() {
        final String colour = given().contentType(ContentType.JSON)
            .body("{\"name\":\"No Colour\"}")
            .post("/api/v1/actions")
            .then().statusCode(201)
            .extract().path("colour");

        assertThat(colour)
            .as("an omitted colour should be suggested, not left as the neutral slate")
            .isNotEqualTo(ActionValidation.DEFAULT_COLOUR)
            .isIn(ActionColours.PALETTE);
    }

    @Test
    void create_absentColour_avoidsTheColoursAlreadyInUse() {
        final List<String> inUse = ActionColours.PALETTE.subList(0, ActionColours.PALETTE.size() - 1);
        runInTx(() -> {
            for (int i = 0; i < inUse.size(); i++) {
                newAction(primaryId, "Action-" + i).colour = inUse.get(i);
            }
        });

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"No Colour"}
                        """)
                .post("/api/v1/actions")
                .then().statusCode(201)
                .body("colour", equalTo(ActionColours.PALETTE.getLast()));
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
                .body("message", containsString("at most 100"));
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
    void update_validNameWithInvalidColour_rejectedAndNothingPersisted() {
        // A rename paired with a malformed colour must be rejected wholesale: the name would be assigned to the managed entity before the colour
        // is validated, so unless every rejection precedes the first mutation the rename would be silently flushed on commit despite the 400.
        final Action action = newActionInTx(primaryId, "Running");

        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Trail running","colour":"not-a-colour"}
                        """)
                .patch("/api/v1/actions/" + action.id)
                .then().statusCode(400)
                .body("message", containsString("colour"));

        runInTx(() -> assertThat(java.util.Objects.requireNonNull(Action.<Action>findById(action.id)).name)
            .as("a rename paired with an invalid colour must not be persisted")
            .isEqualTo("Running"));
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

    // ── Random colour ─────────────────────────────────────────────────────────

    @Test
    void randomColour_returnsAPaletteColour() {
        final String colour = given().get("/api/v1/actions/random-colour")
            .then().statusCode(200)
            .extract().path("colour");

        assertThat(colour)
            .as("the suggested colour should come from the palette")
            .isIn(ActionColours.PALETTE);
    }

    @Test
    void randomColour_avoidsTheColoursAlreadyInUse() {
        final List<String> inUse = ActionColours.PALETTE.subList(0, ActionColours.PALETTE.size() - 1);
        runInTx(() -> {
            for (int i = 0; i < inUse.size(); i++) {
                newAction(primaryId, "Action-" + i).colour = inUse.get(i);
            }
        });

        final String colour = given().get("/api/v1/actions/random-colour")
            .then().statusCode(200)
            .extract().path("colour");

        assertThat(colour)
            .as("the only palette colour not in use should be the one suggested")
            .isEqualTo(ActionColours.PALETTE.getLast());
    }

    @Test
    void randomColour_ignoresAnotherUsersColours() {
        runInTx(() -> {
            for (int i = 0; i < ActionColours.PALETTE.size(); i++) {
                newAction(otherId, "Action-" + i).colour = ActionColours.PALETTE.get(i);
            }
        });

        final String colour = given().get("/api/v1/actions/random-colour")
            .then().statusCode(200)
            .extract().path("colour");

        assertThat(colour)
            .as("another user's colours should not narrow this user's suggestions")
            .isIn(ActionColours.PALETTE);
    }

    @Test
    void randomColour_everyPaletteColourInUse_generatesFreshColour() {
        runInTx(() -> {
            for (int i = 0; i < ActionColours.PALETTE.size(); i++) {
                newAction(primaryId, "Action-" + i).colour = ActionColours.PALETTE.get(i);
            }
        });

        final String colour = given().get("/api/v1/actions/random-colour")
            .then().statusCode(200)
            .extract().path("colour");

        assertThat(colour)
            .as("an exhausted palette should yield a fresh colour, not a repeat")
            .matches("^#[0-9a-f]{6}$")
            .isNotIn(ActionColours.PALETTE);
    }

    @Test
    void randomColour_suggestionIsAcceptedByCreate() {
        final String colour = given().get("/api/v1/actions/random-colour")
            .then().statusCode(200)
            .extract().path("colour");

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Running\",\"colour\":\"" + colour + "\"}")
                .post("/api/v1/actions")
                .then().statusCode(201)
                .body("colour", equalTo(colour));
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
