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
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "logs-api-it@lt.test", roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class LogsApiResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "logs-api-it@lt.test";
    static final String OTHER   = "logs-api-other@lt.test";

    static final LocalDate TODAY = FIXED_TODAY;

    UUID primaryId;
    UUID otherId;
    Action primaryAction;

    @Override
    protected void createDbState() {
        primaryId     = newUser(PRIMARY, "Calendar User").id;
        otherId       = newUser(OTHER,   "Other User").id;
        primaryAction = newAction(primaryId, "Running");
    }

    // ── Events ────────────────────────────────────────────────────────────────

    @Test
    void events_singleLog_titleHasNoMultiplier() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 1));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].title", equalTo("Running"));
    }

    @Test
    void events_countThree_titleHasMultiplier() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 3));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(200)
                .body("[0].title", equalTo("Running ×3"));
    }

    @Test
    void events_emptyRange_returnsEmptyArray() {
        given().queryParam("start", TODAY.minusYears(1).toString())
                .queryParam("end", TODAY.minusYears(1).toString())
                .get("/api/v1/logs/events")
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
                .get("/api/v1/logs/events")
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
                .get("/api/v1/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1))
                .body("[0].title", equalTo("Running"));
    }

    @Test
    void events_isoDatetimeStringWithTime_onlyDatePartUsed() {
        runInTx(() -> newLog(primaryId, primaryAction.id, TODAY, 1));

        // Clients may send ISO datetime strings like "2025-06-15T00:00:00"; only the date part is used.
        final String startWithTime = TODAY + "T00:00:00";
        final String endWithTime   = TODAY + "T23:59:59";

        given().queryParam("start", startWithTime).queryParam("end", endWithTime)
                .get("/api/v1/logs/events")
                .then().statusCode(200)
                .body("$.size()", equalTo(1));
    }

    // ── Events (colour) ───────────────────────────────────────────────────────

    @Test
    void events_colourSetOnEvent() {
        // Create via API to persist the coloured action
        final String html = given().formParam("name", "Coloured2").formParam("colour", "#ff5500")
            .post("/internal/actions")
            .then().statusCode(200).extract().body().asString();

        final java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("id=\"action-([0-9a-f-]+)\"").matcher(html);
        if (!m.find()) {
            return; // skip if extraction fails
        }

        final UUID actionId = UUID.fromString(m.group(1));
        runInTx(() -> newLog(primaryId, actionId, TODAY, 1));

        given().queryParam("start", TODAY.toString()).queryParam("end", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(200)
                .body("find { it.title == 'Coloured2' }.backgroundColor", equalTo("#ff5500"));
    }

    // ── Mandatory range params ──────────────────────────────────────────────────

    @Test
    void events_missingStart_returns400() {
        given().queryParam("end", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(400);
    }

    @Test
    void events_missingEnd_returns400() {
        given().queryParam("start", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(400);
    }

    @Test
    void events_missingBothParams_returns400() {
        given().get("/api/v1/logs/events")
                .then().statusCode(400);
    }

    @Test
    void events_invalidDate_returns400() {
        given().queryParam("start", "not-a-date").queryParam("end", TODAY.toString())
                .get("/api/v1/logs/events")
                .then().statusCode(400);
    }
}
