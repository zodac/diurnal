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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the public logs API's day read and write endpoints ({@code /api/v1/logs/{date}} and {@code /api/v1/logs/{date}/{actionId}}):
 * set/increment/decrement/delete semantics, the daily-cap rejection (an over-cap write is refused, never silently clamped), the future-date rule (on
 * the frozen clock) and ownership isolation.
 */
@QuarkusTest
@TestSecurity(user = "logs-api-write-it@lt.test", roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class LogsApiWriteIT extends IntegrationTestBase {

    static final String PRIMARY = "logs-api-write-it@lt.test";
    static final String OTHER   = "logs-api-write-other@lt.test";

    static final LocalDate TODAY    = FIXED_TODAY;
    static final LocalDate TOMORROW = FIXED_TODAY.plusDays(1);

    UUID primaryId;
    UUID otherId;
    Action action;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Logs API Writer").id;
        otherId   = newUser(OTHER,   "Other User").id;
        action    = newAction(primaryId, "Running");
    }

    // ── Day read ──────────────────────────────────────────────────────────────

    @Test
    void dayLogs_noEntries_returnsEmptyArray() {
        given().get("/api/v1/logs/" + TODAY)
                .then().statusCode(200)
                .body("$.size()", equalTo(0));
    }

    @Test
    void dayLogs_entriesSortedByName_onlyCurrentUsers() {
        runInTx(() -> {
            final Action zumba = newAction(primaryId, "Zumba");
            final Action otherAction = newAction(otherId, "Cycling");
            newLog(primaryId, action.id, TODAY, 2);
            newLog(primaryId, zumba.id, TODAY, 5);
            newLog(otherId, otherAction.id, TODAY, 9);
        });

        given().get("/api/v1/logs/" + TODAY)
                .then().statusCode(200)
                .body("$.size()", equalTo(2))
                .body("[0].name", equalTo("Running"))
                .body("[0].count", equalTo(2))
                .body("[1].name", equalTo("Zumba"))
                .body("[1].count", equalTo(5));
    }

    @Test
    void dayLogs_invalidDate_returns400() {
        given().get("/api/v1/logs/not-a-date")
                .then().statusCode(400);
    }

    // ── Set ───────────────────────────────────────────────────────────────────

    @Test
    void set_createsEntryWithCount() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"count":3}
                        """)
                .put("/api/v1/logs/" + TODAY + "/" + action.id)
                .then().statusCode(200)
                .body("count", equalTo(3))
                .body("date", equalTo(TODAY.toString()));

        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY).count)
            .as("the set count should be persisted")
            .isEqualTo(3));
    }

    @Test
    void set_zero_removesEntry() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 4));

        given().contentType(ContentType.JSON)
                .body("""
                        {"count":0}
                        """)
                .put("/api/v1/logs/" + TODAY + "/" + action.id)
                .then().statusCode(200)
                .body("count", equalTo(0));

        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY))
            .as("a count of zero should remove the day's entry")
            .isNull());
    }

    @Test
    void set_aboveCap_isRejected() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"count":1500}
                        """)
                .put("/api/v1/logs/" + TODAY + "/" + action.id)
                .then().statusCode(400)
                .body("message", containsString("999"));

        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY))
            .as("an over-cap set must be rejected without creating an entry, never silently clamped")
            .isNull());
    }

    @Test
    void set_missingCount_returns400() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .put("/api/v1/logs/" + TODAY + "/" + action.id)
                .then().statusCode(400)
                .body("message", containsString("count"));
    }

    @Test
    void set_negativeCount_returns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"count":-1}
                        """)
                .put("/api/v1/logs/" + TODAY + "/" + action.id)
                .then().statusCode(400);
    }

    @Test
    void set_futureDate_returns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"count":1}
                        """)
                .put("/api/v1/logs/" + TOMORROW + "/" + action.id)
                .then().statusCode(400)
                .body("message", containsString("future"));
    }

    @Test
    void set_otherUsersAction_returns404() {
        final Action otherAction = newActionInTx(otherId, "Cycling");

        given().contentType(ContentType.JSON)
                .body("""
                        {"count":1}
                        """)
                .put("/api/v1/logs/" + TODAY + "/" + otherAction.id)
                .then().statusCode(404);
    }

    // ── Increment / decrement ─────────────────────────────────────────────────

    @Test
    void increment_noAmount_defaultsToOne() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .post("/api/v1/logs/" + TODAY + "/" + action.id + "/increment")
                .then().statusCode(200)
                .body("count", equalTo(1));
    }

    @Test
    void increment_explicitAmount_isApplied() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 2));

        given().contentType(ContentType.JSON)
                .body("""
                        {"amount":5}
                        """)
                .post("/api/v1/logs/" + TODAY + "/" + action.id + "/increment")
                .then().statusCode(200)
                .body("count", equalTo(7));
    }

    @Test
    void increment_pastCap_isRejected() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 998));

        given().contentType(ContentType.JSON)
                .body("""
                        {"amount":10}
                        """)
                .post("/api/v1/logs/" + TODAY + "/" + action.id + "/increment")
                .then().statusCode(400)
                .body("message", containsString("999"));

        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY).count)
            .as("an increment that would exceed the cap must be rejected, leaving the count unchanged")
            .isEqualTo(998));
    }

    @Test
    void increment_exactlyToCap_isApplied() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 998));

        given().contentType(ContentType.JSON)
                .body("""
                        {"amount":1}
                        """)
                .post("/api/v1/logs/" + TODAY + "/" + action.id + "/increment")
                .then().statusCode(200)
                .body("count", equalTo(999));
    }

    @Test
    void increment_zeroAmount_returns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"amount":0}
                        """)
                .post("/api/v1/logs/" + TODAY + "/" + action.id + "/increment")
                .then().statusCode(400)
                .body("message", containsString("amount"));
    }

    @Test
    void increment_futureDate_returns400() {
        given().contentType(ContentType.JSON)
                .body("{}")
                .post("/api/v1/logs/" + TOMORROW + "/" + action.id + "/increment")
                .then().statusCode(400);
    }

    @Test
    void decrement_reachingZero_removesEntry() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 1));

        given().contentType(ContentType.JSON)
                .body("{}")
                .post("/api/v1/logs/" + TODAY + "/" + action.id + "/decrement")
                .then().statusCode(200)
                .body("count", equalTo(0));

        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY))
            .as("decrementing to zero should remove the day's entry")
            .isNull());
    }

    @Test
    void decrement_belowZero_floorsAtZero() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 2));

        given().contentType(ContentType.JSON)
                .body("""
                        {"amount":10}
                        """)
                .post("/api/v1/logs/" + TODAY + "/" + action.id + "/decrement")
                .then().statusCode(200)
                .body("count", equalTo(0));
    }

    @Test
    void decrement_otherUsersAction_returns404() {
        final Action otherAction = newActionInTx(otherId, "Cycling");

        given().contentType(ContentType.JSON)
                .body("{}")
                .post("/api/v1/logs/" + TODAY + "/" + otherAction.id + "/decrement")
                .then().statusCode(404);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removesEntry() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 3));

        given().delete("/api/v1/logs/" + TODAY + "/" + action.id)
                .then().statusCode(204);

        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY))
            .as("the day's entry should be removed")
            .isNull());
    }

    @Test
    void delete_missingEntry_isNoOp204() {
        given().delete("/api/v1/logs/" + TODAY + "/" + action.id)
                .then().statusCode(204);
    }

    @Test
    void delete_unknownAction_returns404() {
        given().delete("/api/v1/logs/" + TODAY + "/" + UUID.randomUUID())
                .then().statusCode(404);
    }

    private Action newActionInTx(final UUID userId, final String name) {
        final Action[] holder = new Action[1];
        runInTx(() -> holder[0] = newAction(userId, name));
        return holder[0];
    }
}
