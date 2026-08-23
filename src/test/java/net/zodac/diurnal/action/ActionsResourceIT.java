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
import static net.zodac.diurnal.http.HttpStatusCodes.CONFLICT;
import static net.zodac.diurnal.http.HttpStatusCodes.NO_CONTENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizePref;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.user.UserSettings;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "actions-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class ActionsResourceIT extends IntegrationTestBase {

    private static final Pattern ACTION_ID_ATTRIBUTE = Pattern.compile("id=\"action-(?<id>[0-9a-f-]+)\"");

    private static final String PRIMARY = "actions-it@lt.test";
    private static final String OTHER = "actions-other@lt.test";

    private UUID primaryId;
    private UUID otherId;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Actions User").id;
        otherId = newUser(OTHER, "Other User").id;
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Test
    void createAction_validNameAndColour_returnsHtmlWithNameAndColour() {
        given().formParam("name", "Running").formParam("colour", "#ff0000")
            .post("/internal/actions")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .contentType("text/html")
            .body(containsString("Running"))
            .body(containsString("#ff0000"));
    }

    @Test
    void createAction_trimsName() {
        given().formParam("name", "  Yoga  ").formParam("colour", "#6366f1")
            .post("/internal/actions")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("Yoga"))
            .body(not(containsString("  Yoga  ")));
    }

    @Test
    void createAction_blankName_returns409WithHxRetarget() {
        given().formParam("name", "   ").formParam("colour", "#6366f1")
            .post("/internal/actions")
            .then().statusCode(CONFLICT)
            .header("HX-Retarget", "#action-error");
    }

    @Test
    void createAction_duplicateName_returns409() {
        given().formParam("name", "Cycling").formParam("colour", "#6366f1").post("/internal/actions")
            .then().statusCode(Response.Status.OK.getStatusCode());

        given().formParam("name", "Cycling").formParam("colour", "#6366f1").post("/internal/actions")
            .then().statusCode(CONFLICT)
            .header("HX-Retarget", "#action-error");
    }

    @Test
    void createAction_sameNameAsAnotherUsersAction_succeeds() {
        // Name uniqueness is scoped per-user: another user already owning "Cycling"
        // must not block this user from creating their own action of the same name.
        runInTx(() -> newAction(otherId, "Cycling"));

        given().formParam("name", "Cycling").formParam("colour", "#6366f1").post("/internal/actions")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("Cycling"));

        assertThat(Action.count("name = ?1", "Cycling"))
            .as("both users should own their own action of the same name")
            .isEqualTo(2L);
    }

    @Test
    void createAction_invalidColour_isRejected() {
        // The colour rule lives in the shared ActionService, so a malformed colour is rejected on the web
        // surface too (never silently corrected), not just on the API.
        given().formParam("name", "Swimming").formParam("colour", "not-a-colour")
            .post("/internal/actions")
            .then().statusCode(CONFLICT)
            .body(containsString("colour is invalid"));
    }

    @Test
    void createAction_validHexColour_preserved() {
        given().formParam("name", "Hiking").formParam("colour", "#abcdef")
            .post("/internal/actions")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("#abcdef"));
    }

    // ── Random colour ──────────────────────────────────────────────────────────

    @Test
    void randomColour_returnsAPaletteColourAsJson() {
        final String colour = given().get("/internal/actions/random-colour")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .contentType("application/json")
            .extract().path("colour");

        assertThat(colour)
            .as("the suggested colour should come from the palette")
            .isIn(ActionColours.PALETTE);
    }

    @Test
    void randomColour_avoidsTheColoursAlreadyInUse() {
        final List<String> inUse = ActionColours.PALETTE.subList(0, ActionColours.PALETTE.size() - 1);
        for (int i = 0; i < inUse.size(); i++) {
            given().formParam("name", "Action-" + i).formParam("colour", inUse.get(i))
                .post("/internal/actions")
                .then().statusCode(Response.Status.OK.getStatusCode());
        }

        final String colour = given().get("/internal/actions/random-colour")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .extract().path("colour");

        assertThat(colour)
            .as("the only palette colour not in use should be the one suggested")
            .isEqualTo(ActionColours.PALETTE.getLast());
    }

    // ── List / pagination ──────────────────────────────────────────────────────

    @Test
    void actionsList_noActions_returnsEmptyBody() {
        given().get("/internal/actions/list")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(not(containsString("<div id=\"action-")));
    }

    @Test
    void actionsList_exactlyOnePage_noPaginationControls() {
        // Exactly a full page of actions fits without spilling onto a second page.
        runInTx(() -> createActions(UserSettings.DEFAULT_PAGE_SIZE));
        given().queryParam("page", 1).get("/internal/actions/list")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(not(containsString("Next")))
            .body(not(containsString("Previous")));
    }

    @Test
    void actionsList_multiplePages_page1_showsNextButton() {
        // One more than a page forces a second page, so page 1 offers a Next control.
        runInTx(() -> createActions(UserSettings.DEFAULT_PAGE_SIZE + 1));
        given().queryParam("page", 1).get("/internal/actions/list")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("Next"));
    }

    @Test
    void actionsList_multiplePages_page2_showsPreviousButton() {
        runInTx(() -> createActions(UserSettings.DEFAULT_PAGE_SIZE + 1));
        given().queryParam("page", 2).get("/internal/actions/list")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("Previous"));
    }

    @Test
    void actionsList_pageNumberBeyondTotal_clampsToLastPage() {
        runInTx(() -> createActions(UserSettings.DEFAULT_PAGE_SIZE));
        given().queryParam("page", 99).get("/internal/actions/list")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(not(containsString("Next")));
    }

    @Test
    void actionsList_sectionOverride_pagesByTheActionsValueRatherThanTheGeneralOne() {
        // Two full general pages' worth of actions, with the Actions section pinned to a single row: the
        // list must page by the override, not by the preference every other list follows.
        runInTx(() -> {
            createActions(UserSettings.DEFAULT_PAGE_SIZE);
            final User user = User.findByEmail(PRIMARY).orElseThrow();
            user.pageSizes = List.of(new PageSizePref(PageSection.ACTIONS.key(), 1));
            user.persist();
        });

        given().queryParam("page", 1).get("/internal/actions/list")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("Action01"))
            .body(not(containsString("Action02")))
            .body(containsString("Next"));
    }

    @Test
    void actionsList_overrideForAnotherSection_leavesTheActionsListOnTheGeneralValue() {
        runInTx(() -> {
            createActions(UserSettings.DEFAULT_PAGE_SIZE);
            final User user = User.findByEmail(PRIMARY).orElseThrow();
            user.pageSizes = List.of(new PageSizePref(PageSection.NOTES.key(), 1));
            user.persist();
        });

        given().queryParam("page", 1).get("/internal/actions/list")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("Action01"))
            .body(containsString(String.format("Action%02d", UserSettings.DEFAULT_PAGE_SIZE)))
            .body(not(containsString("Next")));
    }

    @Test
    void actionsList_searchFiltersCaseInsensitively() {
        given().formParam("name", "Morning Run").formParam("colour", "#6366f1").post("/internal/actions");
        given().formParam("name", "Evening Walk").formParam("colour", "#6366f1").post("/internal/actions");

        given().queryParam("q", "MORNING").get("/internal/actions/list")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("Morning Run"))
            .body(not(containsString("Evening Walk")));
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Test
    void updateAction_validChange_returnsUpdatedHtml() {
        final UUID id = createActionAndGetId("OldName");
        given().formParam("name", "NewName").formParam("colour", "#123456")
            .post("/internal/actions/" + id)
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("NewName"))
            .body(containsString("#123456"));
    }

    @Test
    void updateAction_blankName_returns409() {
        final UUID id = createActionAndGetId("ToRename");
        given().formParam("name", "").formParam("colour", "#6366f1")
            .post("/internal/actions/" + id)
            .then().statusCode(CONFLICT)
            .header("HX-Retarget", "#action-error");
    }

    @Test
    void updateAction_renameToExistingName_returns409() {
        createActionAndGetId("Existing");
        final UUID id = createActionAndGetId("ToRename");
        given().formParam("name", "Existing").formParam("colour", "#6366f1")
            .post("/internal/actions/" + id)
            .then().statusCode(CONFLICT);
    }

    @Test
    void updateAction_renameToOwnCurrentName_returns200() {
        final UUID id = createActionAndGetId("SameName");
        given().formParam("name", "SameName").formParam("colour", "#6366f1")
            .post("/internal/actions/" + id)
            .then().statusCode(Response.Status.OK.getStatusCode());
    }

    @Test
    void updateAction_otherUsersAction_returns404() {
        // Create the action owned by the OTHER user directly in DB
        final Action[] holder = new Action[1];
        runInTx(() -> holder[0] = newAction(otherId, "OtherAction"));
        given().formParam("name", "Hacked").formParam("colour", "#6366f1")
            .post("/internal/actions/" + holder[0].id)
            .then().statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteAction_ownAction_returns204AndHardDeletesIt() {
        final UUID id = createActionAndGetId("ToDelete");
        given().post("/internal/actions/" + id + "/delete")
            .then().statusCode(NO_CONTENT);

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

        given().post("/internal/actions/" + holder[0].id + "/delete")
            .then().statusCode(NO_CONTENT);

        final long logCount = net.zodac.diurnal.log.ActionLog.count("actionId = ?1", holder[0].id);
        assertThat(logCount)
            .as("unexpected value")
            .isZero();
    }

    @Test
    void deleteAction_otherUsersAction_returns404() {
        final Action[] holder = new Action[1];
        runInTx(() -> holder[0] = newAction(otherId, "OtherToDelete"));
        given().post("/internal/actions/" + holder[0].id + "/delete")
            .then().statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    void deleteAction_thenRecreateSameName_succeeds() {
        // Hard-delete frees the name at the DB level, so the same name can be reused
        // (a soft-delete would leave the row and trip the (user_id, name) unique constraint).
        final UUID firstId = createActionAndGetId("Recreatable");
        given().post("/internal/actions/" + firstId + "/delete")
            .then().statusCode(NO_CONTENT);

        final UUID secondId = createActionAndGetId("Recreatable");
        assertThat(secondId)
            .as("recreated action should be a brand-new row")
            .isNotEqualTo(firstId);

        assertThat(Action.count("userId = ?1 and name = ?2", primaryId, "Recreatable"))
            .as("exactly one live action should carry the reused name")
            .isEqualTo(1L);
    }

    // ── Partial fragments ──────────────────────────────────────────────────────

    @Test
    void viewItem_ownAction_returns200WithRow() {
        final UUID id = createActionAndGetId("ViewMe");
        given().get("/internal/actions/" + id)
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("ViewMe"));
    }

    @Test
    void viewItem_unknownId_returns404() {
        given().get("/internal/actions/9999")
            .then().statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    @Test
    void confirmDelete_ownAction_returns200() {
        final UUID id = createActionAndGetId("ConfirmMe");
        given().get("/internal/actions/" + id + "/confirm-delete")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .body(containsString("Delete this action?"));
    }

    @Test
    void confirmDelete_unknownId_returns404() {
        given().get("/internal/actions/" + UUID.randomUUID() + "/confirm-delete")
            .then().statusCode(Response.Status.NOT_FOUND.getStatusCode());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createActions(final int count) {
        for (int i = 1; i <= count; i++) {
            newAction(primaryId, String.format("Action%02d", i));
        }
    }

    private static UUID createActionAndGetId(final String name) {
        final String html = given().formParam("name", name).formParam("colour", "#6366f1")
            .post("/internal/actions")
            .then().statusCode(Response.Status.OK.getStatusCode())
            .extract().body().asString();
        // The returned HTML contains id="action-{uuid}"
        final Matcher matcher = ACTION_ID_ATTRIBUTE.matcher(html);
        if (matcher.find()) {
            return UUID.fromString(matcher.group("id"));
        }
        throw new IllegalStateException("Could not find action id in response: " + html);
    }
}
