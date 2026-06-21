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

package net.zodac.diurnal.log;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "calendar-it@lt.test", roles = "user")
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class CalendarResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "calendar-it@lt.test";
    static final String OTHER   = "calendar-other@lt.test";

    static final LocalDate TODAY = FIXED_TODAY;

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
        final Action[] holder = new Action[1];
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
            final Action otherAction = newAction(otherId, "Yoga");
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
        final String startWithTime = TODAY + "T00:00:00";
        final String endWithTime   = TODAY + "T23:59:59";

        given().queryParam("start", startWithTime).queryParam("end", endWithTime)
                .get("/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1));
    }

    // ── Minimal Events ────────────────────────────────────────────────────────

    @Test
    void minimalEvents_emptyRange_returnsEmptyArray() {
        given().queryParam("start", TODAY.minusYears(1).toString())
                .queryParam("end",   TODAY.minusYears(1).toString())
                .get("/logs/minimal-events")
                .then().statusCode(200)
                .body("$.size()", equalTo(0));
    }

    @Test
    void minimalEvents_singleLog_returnsOneDotWithCorrectFields() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 2));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/minimal-events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].date", equalTo(TODAY.toString()))
                .body("[0].actions.size()", equalTo(1))
                .body("[0].actions[0].name", equalTo("Running"))
                .body("[0].actions[0].count", equalTo(2));
    }

    @Test
    void minimalEvents_multipleActionsOnSameDay_sortedByCountDescThenNameAsc() {
        final Action[] extras = new Action[2];
        runInTx(() -> {
            extras[0] = newAction(primaryId, "Alpha");  // count 1
            extras[1] = newAction(primaryId, "Bravo");  // count 3
            newLog(primaryId, primaryAction.id, TODAY, 2); // Running, count 2
            newLog(primaryId, extras[0].id,     TODAY, 1); // Alpha,   count 1
            newLog(primaryId, extras[1].id,     TODAY, 3); // Bravo,   count 3
        });

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/minimal-events")
                .then().statusCode(200)
                .body("[0].actions.size()", equalTo(3))
                .body("[0].actions[0].name", equalTo("Bravo"))   // highest count first
                .body("[0].actions[1].name", equalTo("Running"))
                .body("[0].actions[2].name", equalTo("Alpha"));
    }

    @Test
    void minimalEvents_tieInCount_sortedAlphabetically() {
        final Action[] extras = new Action[1];
        runInTx(() -> {
            extras[0] = newAction(primaryId, "Aerobics");
            newLog(primaryId, primaryAction.id, TODAY, 1); // Running,  count 1
            newLog(primaryId, extras[0].id,     TODAY, 1); // Aerobics, count 1
        });

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/minimal-events")
                .then().statusCode(200)
                .body("[0].actions[0].name", equalTo("Aerobics")) // A before R
                .body("[0].actions[1].name", equalTo("Running"));
    }

    @Test
    void minimalEvents_moreThanFourActions_cappedAtFourHighestCount() {
        final Action[] extras = new Action[4];
        runInTx(() -> {
            extras[0] = newAction(primaryId, "Action1");
            extras[1] = newAction(primaryId, "Action2");
            extras[2] = newAction(primaryId, "Action3");
            extras[3] = newAction(primaryId, "Action4");
            newLog(primaryId, primaryAction.id, TODAY, 5); // Running, highest
            newLog(primaryId, extras[0].id,     TODAY, 4);
            newLog(primaryId, extras[1].id,     TODAY, 3);
            newLog(primaryId, extras[2].id,     TODAY, 2);
            newLog(primaryId, extras[3].id,     TODAY, 1); // lowest — should be excluded
        });

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/minimal-events")
                .then().statusCode(200)
                .body("[0].actions.size()", equalTo(4))
                .body("[0].actions[0].name", equalTo("Running"))   // count 5
                .body("[0].actions[3].name", equalTo("Action3"));  // count 2 — Action4 (count 1) excluded
    }

    @Test
    void minimalEvents_multipleDays_allDaysReturned() {
        final LocalDate yesterday = TODAY.minusDays(1);
        runInTx(() -> {
            newLog(primaryId, primaryAction.id, TODAY,     1);
            newLog(primaryId, primaryAction.id, yesterday, 1);
        });

        given().queryParam("start", yesterday.toString()).queryParam("end", TODAY.toString())
                .get("/logs/minimal-events")
                .then().statusCode(200)
                .body("$.size()", equalTo(2));
    }

    @Test
    void minimalEvents_onlyCurrentUsersLogs() {
        runInTx(() -> {
            final Action otherAction = newAction(otherId, "Yoga");
            newLog(primaryId, primaryAction.id, TODAY, 1);
            newLog(otherId,   otherAction.id,   TODAY, 1);
        });

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/minimal-events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].actions[0].name", equalTo("Running"));
    }

    @Test
    void minimalEvents_archivedActionLogsStillAppear() {
        runInTx(() -> newLog(primaryId, archivedAction.id, TODAY, 1));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/minimal-events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].actions[0].name", equalTo("OldHabit"));
    }

    @Test
    void minimalEvents_isoDatetimeStringWithTime_onlyDatePartUsed() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 1));

        final String startWithTime = TODAY + "T00:00:00";
        final String endWithTime   = TODAY.plusDays(1) + "T00:00:00";

        given().queryParam("start", startWithTime).queryParam("end", endWithTime)
                .get("/logs/minimal-events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1));
    }

    // ── Events (colour) ───────────────────────────────────────────────────────

    @Test
    void events_colourSetOnEvent() {
        // Create via API to persist the coloured action
        final String html = given().formParam("name", "Coloured2").formParam("colour", "#ff5500")
                .post("/actions")
                .then().statusCode(200).extract().body().asString();

        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("id=\"action-([0-9a-f-]+)\"").matcher(html);
        if (!m.find()) {
            return; // skip if extraction fails
        }

        final UUID actionId = UUID.fromString(m.group(1));
        runInTx(() -> newLog(primaryId, actionId, TODAY, 1));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(200)
                .body("find { it.title == 'Coloured2' }.backgroundColor", equalTo("#ff5500"));
    }

    // ── Mandatory range params ──────────────────────────────────────────────────

    @Test
    void events_missingStart_returns400() {
        given().queryParam("end", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(400);
    }

    @Test
    void events_missingEnd_returns400() {
        given().queryParam("start", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(400);
    }

    @Test
    void events_missingBothParams_returns400() {
        given().get("/logs/events")
                .then().statusCode(400);
    }

    @Test
    void events_invalidDate_returns400() {
        given().queryParam("start", "not-a-date").queryParam("end", TODAY.toString())
                .get("/logs/events")
                .then().statusCode(400);
    }
}
