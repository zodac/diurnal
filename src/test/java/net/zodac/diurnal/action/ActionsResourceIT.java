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
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.UserSettings;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "actions-it@lt.test", roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class ActionsResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "actions-it@lt.test";
    static final String OTHER = "actions-other@lt.test";

    UUID primaryId;
    UUID otherId;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Actions User").id;
        otherId = newUser(OTHER, "Other User").id;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Test
    void createAction_validNameAndColour_returnsHtmlWithNameAndColour() {
        given().formParam("name", "Running").formParam("colour", "#ff0000")
            .post("/actions")
            .then().statusCode(200)
            .contentType("text/html")
            .body(containsString("Running"))
            .body(containsString("#ff0000"));
    }

    @Test
    void createAction_trimsName() {
        given().formParam("name", "  Yoga  ").formParam("colour", "#6366f1")
            .post("/actions")
            .then().statusCode(200)
            .body(containsString("Yoga"))
            .body(not(containsString("  Yoga  ")));
    }

    @Test
    void createAction_blankName_returns409WithHxRetarget() {
        given().formParam("name", "   ").formParam("colour", "#6366f1")
            .post("/actions")
            .then().statusCode(409)
            .header("HX-Retarget", "#action-error");
    }

    @Test
    void createAction_duplicateName_returns409() {
        given().formParam("name", "Cycling").formParam("colour", "#6366f1").post("/actions")
            .then().statusCode(200);

        given().formParam("name", "Cycling").formParam("colour", "#6366f1").post("/actions")
            .then().statusCode(409)
            .header("HX-Retarget", "#action-error");
    }

    @Test
    void createAction_sameNameAsAnotherUsersAction_succeeds() {
        // Name uniqueness is scoped per-user: another user already owning "Cycling"
        // must not block this user from creating their own action of the same name.
        runInTx(() -> newAction(otherId, "Cycling"));

        given().formParam("name", "Cycling").formParam("colour", "#6366f1").post("/actions")
            .then().statusCode(200)
            .body(containsString("Cycling"));

        assertThat(Action.count("name = ?1", "Cycling"))
            .as("both users should own their own action of the same name")
            .isEqualTo(2);
    }

    @Test
    void createAction_invalidColour_sanitisedToDefault() {
        given().formParam("name", "Swimming").formParam("colour", "not-a-colour")
            .post("/actions")
            .then().statusCode(200)
            .body(containsString("#64748b"));
    }

    @Test
    void createAction_validHexColour_preserved() {
        given().formParam("name", "Hiking").formParam("colour", "#abcdef")
            .post("/actions")
            .then().statusCode(200)
            .body(containsString("#abcdef"));
    }

    // ── List / pagination ──────────────────────────────────────────────────────

    @Test
    void actionsList_noActions_returnsEmptyBody() {
        given().get("/actions/list")
            .then().statusCode(200)
            .body(not(containsString("<div id=\"action-")));
    }

    @Test
    void actionsList_exactlyOnePage_noPaginationControls() {
        // Exactly a full page of actions fits without spilling onto a second page.
        runInTx(() -> createActions(UserSettings.DEFAULT_PAGE_SIZE));
        given().queryParam("page", 1).get("/actions/list")
            .then().statusCode(200)
            .body(not(containsString("Next")))
            .body(not(containsString("Previous")));
    }

    @Test
    void actionsList_multiplePages_page1_showsNextButton() {
        // One more than a page forces a second page, so page 1 offers a Next control.
        runInTx(() -> createActions(UserSettings.DEFAULT_PAGE_SIZE + 1));
        given().queryParam("page", 1).get("/actions/list")
            .then().statusCode(200)
            .body(containsString("Next"));
    }

    @Test
    void actionsList_multiplePages_page2_showsPreviousButton() {
        runInTx(() -> createActions(UserSettings.DEFAULT_PAGE_SIZE + 1));
        given().queryParam("page", 2).get("/actions/list")
            .then().statusCode(200)
            .body(containsString("Previous"));
    }

    @Test
    void actionsList_pageNumberBeyondTotal_clampsToLastPage() {
        runInTx(() -> createActions(UserSettings.DEFAULT_PAGE_SIZE));
        given().queryParam("page", 99).get("/actions/list")
            .then().statusCode(200)
            .body(not(containsString("Next")));
    }

    @Test
    void actionsList_searchFiltersCaseInsensitively() {
        given().formParam("name", "Morning Run").formParam("colour", "#6366f1").post("/actions");
        given().formParam("name", "Evening Walk").formParam("colour", "#6366f1").post("/actions");

        given().queryParam("q", "MORNING").get("/actions/list")
            .then().statusCode(200)
            .body(containsString("Morning Run"))
            .body(not(containsString("Evening Walk")));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Test
    void updateAction_validChange_returnsUpdatedHtml() {
        final UUID id = createActionAndGetId("OldName");
        given().formParam("name", "NewName").formParam("colour", "#123456")
            .post("/actions/" + id)
            .then().statusCode(200)
            .body(containsString("NewName"))
            .body(containsString("#123456"));
    }

    @Test
    void updateAction_blankName_returns409() {
        final UUID id = createActionAndGetId("ToRename");
        given().formParam("name", "").formParam("colour", "#6366f1")
            .post("/actions/" + id)
            .then().statusCode(409)
            .header("HX-Retarget", "#action-error");
    }

    @Test
    void updateAction_renameToExistingName_returns409() {
        createActionAndGetId("Existing");
        final UUID id = createActionAndGetId("ToRename");
        given().formParam("name", "Existing").formParam("colour", "#6366f1")
            .post("/actions/" + id)
            .then().statusCode(409);
    }

    @Test
    void updateAction_renameToOwnCurrentName_returns200() {
        final UUID id = createActionAndGetId("SameName");
        given().formParam("name", "SameName").formParam("colour", "#6366f1")
            .post("/actions/" + id)
            .then().statusCode(200);
    }

    @Test
    void updateAction_otherUsersAction_returns404() {
        // Create the action owned by the OTHER user directly in DB
        final Action[] holder = new Action[1];
        runInTx(() -> holder[0] = newAction(otherId, "OtherAction"));
        given().formParam("name", "Hacked").formParam("colour", "#6366f1")
            .post("/actions/" + holder[0].id)
            .then().statusCode(404);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteAction_ownAction_returns204AndHardDeletesIt() {
        final UUID id = createActionAndGetId("ToDelete");
        given().post("/actions/" + id + "/delete")
            .then().statusCode(204);

        final Action found = Action.<Action>find("id = ?1", id).firstResult();
        assertThat(found)
            .as("deleted action should no longer exist in DB")
            .isNull();
    }

    @Test
    void deleteAction_deletesAssociatedLogs() {
        final Action[] holder = new Action[1];
        runInTx(() -> {
            holder[0] = newAction(primaryId, "WithLogs");
            newLog(primaryId, holder[0].id, java.time.LocalDate.now(), 1);
        });

        given().post("/actions/" + holder[0].id + "/delete")
            .then().statusCode(204);

        final long logCount = net.zodac.diurnal.log.ActionLog.count("actionId = ?1", holder[0].id);
        assertThat(logCount)
            .as("unexpected value")
            .isEqualTo(0);
    }

    @Test
    void deleteAction_otherUsersAction_returns404() {
        final Action[] holder = new Action[1];
        runInTx(() -> holder[0] = newAction(otherId, "OtherToDelete"));
        given().post("/actions/" + holder[0].id + "/delete")
            .then().statusCode(404);
    }

    @Test
    void deleteAction_thenRecreateSameName_succeeds() {
        // Hard-delete frees the name at the DB level, so the same name can be reused
        // (a soft-delete would leave the row and trip the (user_id, name) unique constraint).
        final UUID firstId = createActionAndGetId("Recreatable");
        given().post("/actions/" + firstId + "/delete")
            .then().statusCode(204);

        final UUID secondId = createActionAndGetId("Recreatable");
        assertThat(secondId)
            .as("recreated action should be a brand-new row")
            .isNotEqualTo(firstId);

        assertThat(Action.count("userId = ?1 and name = ?2", primaryId, "Recreatable"))
            .as("exactly one live action should carry the reused name")
            .isEqualTo(1);
    }

    // ── Partial fragments ──────────────────────────────────────────────────────

    @Test
    void viewItem_ownAction_returns200WithRow() {
        final UUID id = createActionAndGetId("ViewMe");
        given().get("/actions/" + id)
            .then().statusCode(200)
            .body(containsString("ViewMe"));
    }

    @Test
    void viewItem_unknownId_returns404() {
        given().get("/actions/" + UUID.randomUUID())
            .then().statusCode(404);
    }

    @Test
    void confirmDelete_ownAction_returns200() {
        final UUID id = createActionAndGetId("ConfirmMe");
        given().get("/actions/" + id + "/confirm-delete")
            .then().statusCode(200)
            .body(containsString("Delete this action?"));
    }

    @Test
    void confirmDelete_unknownId_returns404() {
        given().get("/actions/" + UUID.randomUUID() + "/confirm-delete")
            .then().statusCode(404);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createActions(final int count) {
        for (int i = 1; i <= count; i++) {
            newAction(primaryId, String.format("Action%02d", i));
        }
    }

    private UUID createActionAndGetId(final String name) {
        final String html = given().formParam("name", name).formParam("colour", "#6366f1")
            .post("/actions")
            .then().statusCode(200)
            .extract().body().asString();
        // The returned HTML contains id="action-{uuid}"
        final java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("id=\"action-([0-9a-f-]+)\"").matcher(html);
        if (m.find()) {
            return UUID.fromString(m.group(1));
        }
        throw new IllegalStateException("Could not find action id in response: " + html);
    }
}
