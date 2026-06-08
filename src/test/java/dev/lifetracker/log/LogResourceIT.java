package dev.lifetracker.log;

import dev.lifetracker.IntegrationTestBase;
import dev.lifetracker.action.Action;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "log-it@lt.test", roles = "user")
class LogResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "log-it@lt.test";
    static final String OTHER   = "log-other@lt.test";

    UUID primaryId;
    UUID otherId;
    Action primaryAction;
    Action otherAction;

    // Today in UTC — matches what the app uses (app.timezone defaults to UTC in tests)
    static final LocalDate TODAY = LocalDate.now(ZoneId.of("UTC"));
    static final LocalDate YESTERDAY = TODAY.minusDays(1);
    static final LocalDate TOMORROW  = TODAY.plusDays(1);

    @Override
    protected void createDbState() {
        primaryId     = newUser(PRIMARY, "Log User").id;
        otherId       = newUser(OTHER,   "Other User").id;
        primaryAction = newAction(primaryId, "PrimaryAction");
        otherAction   = newAction(otherId,   "OtherAction");
    }

    // ── Increment ─────────────────────────────────────────────────────────────

    @Test
    void increment_firstTime_createsLogWithCountOne() {
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment")
                .then().statusCode(200)
                .body(containsString("1"));

        ActionLog log = ActionLog.findEntry(primaryId, primaryAction.id, TODAY);
        assertNotNull(log);
        assertEquals(1, log.count);
    }

    @Test
    void increment_twice_countBecomesTwo() {
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment");
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment")
                .then().statusCode(200)
                .body(containsString("2"));
    }

    @Test
    void increment_at254_countBecomes255() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 254));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment")
                .then().statusCode(200)
                .body(containsString("255"));
    }

    @Test
    void increment_at255_countStays255AndReturns200() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 255));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/increment")
                .then().statusCode(200)
                .body(containsString("255"));

        ActionLog log = ActionLog.findEntry(primaryId, primaryAction.id, TODAY);
        assertEquals(255, log.count);
    }

    @Test
    void increment_futureDate_returns400() {
        given().post("/logs/" + TOMORROW + "/" + primaryAction.id + "/increment")
                .then().statusCode(400);
    }

    @Test
    void increment_otherUsersAction_returns404() {
        given().post("/logs/" + TODAY + "/" + otherAction.id + "/increment")
                .then().statusCode(404);
    }

    // ── Decrement ─────────────────────────────────────────────────────────────

    @Test
    void decrement_fromTwo_becomesOne() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 2));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement")
                .then().statusCode(200)
                .body(containsString("1"));

        assertEquals(1, ActionLog.findEntry(primaryId, primaryAction.id, TODAY).count);
    }

    @Test
    void decrement_fromOne_deletesLogAndReturnsZero() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 1));
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement")
                .then().statusCode(200)
                .body(containsString("0"));

        assertNull(ActionLog.findEntry(primaryId, primaryAction.id, TODAY));
    }

    @Test
    void decrement_noExistingLog_returns200WithZeroIdempotently() {
        // No log exists — should not error
        given().post("/logs/" + TODAY + "/" + primaryAction.id + "/decrement")
                .then().statusCode(200)
                .body(containsString("0"));
    }

    @Test
    void decrement_futureDate_returns400() {
        given().post("/logs/" + TOMORROW + "/" + primaryAction.id + "/decrement")
                .then().statusCode(400);
    }

    @Test
    void decrement_otherUsersAction_returns404() {
        given().post("/logs/" + TODAY + "/" + otherAction.id + "/decrement")
                .then().statusCode(404);
    }

    // ── Day panel ──────────────────────────────────────────────────────────────

    @Test
    void dayPanel_pastDateWithLog_showsActionSortedByCountDesc() {
        Action[] holder = new Action[1];
        runInTx(() -> {
            holder[0] = newAction(primaryId, "AlphaFirst"); // alphabetically first
            newLog(primaryId, primaryAction.id, YESTERDAY, 3);
            newLog(primaryId, holder[0].id, YESTERDAY, 1);
        });

        String body = given().get("/logs/day/" + YESTERDAY)
                .then().statusCode(200)
                .extract().body().asString();

        // PrimaryAction has count=3, AlphaFirst count=1 — PrimaryAction should appear first in HTML
        int posA = body.indexOf("PrimaryAction");
        int posB = body.indexOf("AlphaFirst");
        assertTrue(posA < posB, "Higher count action should appear before lower count action");
    }

    @Test
    void dayPanel_pastDateNoLogs_showsAllActionsAtZero() {
        // count=0 renders the decrement button as disabled (cursor-not-allowed class)
        given().get("/logs/day/" + YESTERDAY)
                .then().statusCode(200)
                .body(containsString("PrimaryAction"))
                .body(containsString("cursor-not-allowed"));
    }

    @Test
    void dayPanel_futureDate_showsFutureMessage() {
        given().get("/logs/day/" + TOMORROW)
                .then().statusCode(200)
                .body(anyOf(containsString("future"), containsString("cannot")));
    }

    // ── Day list pagination ────────────────────────────────────────────────────

    @Test
    void dayList_pageClampedWhenExceedsTotal() {
        // Only 1 action in DB, default pageSize=10 → only 1 page
        given().queryParam("page", 99).get("/logs/day/" + TODAY + "/list")
                .then().statusCode(200)
                .body(containsString("PrimaryAction")); // clamped to page 1
    }

    @Test
    void dayList_searchFilterWorks() {
        runInTx(() -> newAction(primaryId, "Swimming"));
        given().queryParam("q", "swim").get("/logs/day/" + TODAY + "/list")
                .then().statusCode(200)
                .body(containsString("Swimming"))
                .body(not(containsString("PrimaryAction")));
    }

    @Test
    void dayList_fillerRowsOnlyWhenMultiplePages() {
        // With default pageSize=10 and 2 actions, only 1 page — no filler rows needed
        given().get("/logs/day/" + TODAY + "/list")
                .then().statusCode(200)
                .body(not(containsString("filler")));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void assertNotNull(Object o) {
        if (o == null) throw new AssertionError("Expected non-null but was null");
    }

    private static void assertNull(Object o) {
        if (o != null) throw new AssertionError("Expected null but was: " + o);
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual)
            throw new AssertionError("Expected " + expected + " but was " + actual);
    }

    private static void assertTrue(boolean condition, String msg) {
        if (!condition) throw new AssertionError(msg);
    }
}
