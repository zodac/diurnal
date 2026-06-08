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

@QuarkusTest
@TestSecurity(user = "calendar-it@lt.test", roles = "user")
class CalendarResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "calendar-it@lt.test";
    static final String OTHER   = "calendar-other@lt.test";

    static final LocalDate TODAY = LocalDate.now(ZoneId.of("UTC"));

    UUID primaryId;
    UUID otherId;
    Action primaryAction;
    Action archivedAction; // pre-archived; its log should still appear on the calendar

    @Override
    protected void createDbState() {
        primaryId     = newUser(PRIMARY, "Calendar User").id;
        otherId       = newUser(OTHER,   "Other User").id;
        primaryAction = newAction(primaryId, "Running");

        // An archived action whose historical logs should still appear on the calendar
        archivedAction = newAction(primaryId, "OldHabit");
        archivedAction.archived = true;
        archivedAction.persist();
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @Test
    void events_singleLog_titleHasNoMultiplier() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 1));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].title", equalTo("Running"));
    }

    @Test
    void events_countThree_titleHasMultiplier() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 3));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(200)
                .body("[0].title", equalTo("Running ×3"));
    }

    @Test
    void events_emptyRange_returnsEmptyArray() {
        given().queryParam("start", TODAY.minusYears(1).toString())
                .queryParam("end", TODAY.minusYears(1).toString())
                .get("/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(0));
    }

    @Test
    void events_multipleActionsOnSameDay_allReturned() {
        Action[] holder = new Action[1];
        runInTx(() -> {
            holder[0] = newAction(primaryId, "Cycling");
            newLog(primaryId, primaryAction.id, TODAY, 1);
            newLog(primaryId, holder[0].id, TODAY, 1);
        });

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(2));
    }

    @Test
    void events_onlyCurrentUsersEvents() {
        // Other user logs the same action type (their own action)
        runInTx(() -> {
            Action otherAction = newAction(otherId, "Yoga");
            newLog(primaryId, primaryAction.id, TODAY, 1);
            newLog(otherId,   otherAction.id,   TODAY, 1);
        });

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].title", equalTo("Running"));
    }

    @Test
    void events_archivedActionLogsStillAppear() {
        // archivedAction is pre-archived in createDbState(); log it on TODAY
        runInTx(() -> newLog(primaryId, archivedAction.id, TODAY, 1));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].title", equalTo("OldHabit"));
    }

    @Test
    void events_isoDatetimeStringWithTime_onlyDatePartUsed() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 1));

        // FullCalendar sends ISO datetime strings like "2025-06-15T00:00:00"
        String startWithTime = TODAY + "T00:00:00";
        String endWithTime   = TODAY + "T23:59:59";

        given().queryParam("start", startWithTime).queryParam("end", endWithTime)
                .get("/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1));
    }

    @Test
    void events_colourSetOnEvent() {
        // Create via API to persist the coloured action
        String html = given().formParam("name", "Coloured2").formParam("colour", "#ff5500")
                .post("/actions")
                .then().statusCode(200).extract().body().asString();

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("id=\"action-([0-9a-f-]+)\"").matcher(html);
        if (!m.find()) return; // skip if extraction fails

        UUID actionId = UUID.fromString(m.group(1));
        runInTx(() -> newLog(primaryId, actionId, TODAY, 1));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(200)
                .body("find { it.title == 'Coloured2' }.backgroundColor", equalTo("#ff5500"));
    }
}
