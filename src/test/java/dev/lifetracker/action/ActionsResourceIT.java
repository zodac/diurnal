package dev.lifetracker.action;

import dev.lifetracker.IntegrationTestBase;
import dev.lifetracker.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestSecurity(user = "actions-it@lt.test", roles = "user")
class ActionsResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "actions-it@lt.test";
    static final String OTHER   = "actions-other@lt.test";

    UUID primaryId;
    UUID otherId;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Actions User").id;
        otherId   = newUser(OTHER,   "Other User").id;
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
    void createAction_invalidColour_sanitisedToDefault() {
        given().formParam("name", "Swimming").formParam("colour", "not-a-colour")
                .post("/actions")
                .then().statusCode(200)
                .body(containsString("#6366f1"));
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
        runInTx(() -> createActions(10));
        given().queryParam("page", 1).get("/actions/list")
                .then().statusCode(200)
                .body(not(containsString("Next")))
                .body(not(containsString("Previous")));
    }

    @Test
    void actionsList_elevenActions_page1_showsNextButton() {
        runInTx(() -> createActions(11));
        given().queryParam("page", 1).get("/actions/list")
                .then().statusCode(200)
                .body(containsString("Next"));
    }

    @Test
    void actionsList_elevenActions_page2_showsPreviousButton() {
        runInTx(() -> createActions(11));
        given().queryParam("page", 2).get("/actions/list")
                .then().statusCode(200)
                .body(containsString("Previous"));
    }

    @Test
    void actionsList_pageNumberBeyondTotal_clampsToLastPage() {
        runInTx(() -> createActions(5));
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
        UUID id = createActionAndGetId("OldName");
        given().formParam("name", "NewName").formParam("colour", "#123456")
                .post("/actions/" + id)
                .then().statusCode(200)
                .body(containsString("NewName"))
                .body(containsString("#123456"));
    }

    @Test
    void updateAction_blankName_returns409() {
        UUID id = createActionAndGetId("ToRename");
        given().formParam("name", "").formParam("colour", "#6366f1")
                .post("/actions/" + id)
                .then().statusCode(409)
                .header("HX-Retarget", "#action-error");
    }

    @Test
    void updateAction_renameToExistingName_returns409() {
        createActionAndGetId("Existing");
        UUID id = createActionAndGetId("ToRename");
        given().formParam("name", "Existing").formParam("colour", "#6366f1")
                .post("/actions/" + id)
                .then().statusCode(409);
    }

    @Test
    void updateAction_renameToOwnCurrentName_returns200() {
        UUID id = createActionAndGetId("SameName");
        given().formParam("name", "SameName").formParam("colour", "#6366f1")
                .post("/actions/" + id)
                .then().statusCode(200);
    }

    @Test
    void updateAction_otherUsersAction_returns404() {
        // Create the action owned by the OTHER user directly in DB
        Action[] holder = new Action[1];
        runInTx(() -> holder[0] = newAction(otherId, "OtherAction"));
        given().formParam("name", "Hacked").formParam("colour", "#6366f1")
                .post("/actions/" + holder[0].id)
                .then().statusCode(404);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteAction_ownAction_returns204AndArchivesIt() {
        UUID id = createActionAndGetId("ToDelete");
        given().post("/actions/" + id + "/delete")
                .then().statusCode(204);

        // Verify archived in DB — query includes archived to find it
        Action found = Action.<Action>find("id = ?1", id).firstResult();
        assertNotNull(found);
        assertTrue(found.archived);
    }

    @Test
    void deleteAction_deletesAssociatedLogs() {
        Action[] holder = new Action[1];
        runInTx(() -> {
            holder[0] = newAction(primaryId, "WithLogs");
            newLog(primaryId, holder[0].id, java.time.LocalDate.now(), 1);
        });

        given().post("/actions/" + holder[0].id + "/delete")
                .then().statusCode(204);

        long logCount = dev.lifetracker.log.ActionLog.count("actionId = ?1", holder[0].id);
        assertEquals(0, logCount);
    }

    @Test
    void deleteAction_otherUsersAction_returns404() {
        Action[] holder = new Action[1];
        runInTx(() -> holder[0] = newAction(otherId, "OtherToDelete"));
        given().post("/actions/" + holder[0].id + "/delete")
                .then().statusCode(404);
    }

    // ── Partial fragments ──────────────────────────────────────────────────────

    @Test
    void editForm_ownAction_returns200WithForm() {
        UUID id = createActionAndGetId("EditMe");
        given().get("/actions/" + id + "/edit")
                .then().statusCode(200)
                .body(containsString("EditMe"));
    }

    @Test
    void editForm_unknownId_returns404() {
        given().get("/actions/" + UUID.randomUUID() + "/edit")
                .then().statusCode(404);
    }

    @Test
    void confirmDelete_ownAction_returns200() {
        UUID id = createActionAndGetId("ConfirmMe");
        given().get("/actions/" + id + "/confirm-delete")
                .then().statusCode(200);
    }

    @Test
    void confirmDelete_unknownId_returns404() {
        given().get("/actions/" + UUID.randomUUID() + "/confirm-delete")
                .then().statusCode(404);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void createActions(int count) {
        for (int i = 1; i <= count; i++) {
            newAction(primaryId, String.format("Action%02d", i));
        }
    }

    private UUID createActionAndGetId(String name) {
        String html = given().formParam("name", name).formParam("colour", "#6366f1")
                .post("/actions")
                .then().statusCode(200)
                .extract().body().asString();
        // The returned HTML contains id="action-{uuid}"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("id=\"action-([0-9a-f-]+)\"").matcher(html);
        if (m.find()) return UUID.fromString(m.group(1));
        throw new IllegalStateException("Could not find action id in response: " + html);
    }

    private static void assertNotNull(Object o) {
        if (o == null) throw new AssertionError("Expected non-null");
    }

    private static void assertTrue(boolean condition) {
        if (!condition) throw new AssertionError("Expected true");
    }

    private static void assertEquals(long expected, long actual) {
        if (expected != actual)
            throw new AssertionError("Expected " + expected + " but was " + actual);
    }
}
