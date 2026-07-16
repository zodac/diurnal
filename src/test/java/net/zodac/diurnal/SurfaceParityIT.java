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

package net.zodac.diurnal;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * Cross-surface parity guard: drives the SAME inputs through both the web UI's HTMX endpoints ({@code /internal/*}) and the public REST API
 * ({@code /api/v1/*}) and asserts equivalent outcomes (both reject, or both persist the same state). The rules themselves live in the shared
 * {@code ActionService}/{@code LogService}, so this is a belt-and-braces net for anything that bypasses them — statuses differ per surface
 * (banner {@code 409}s vs JSON {@code 400}/{@code 409}s), so the authoritative parity assertion is the resulting database state.
 */
@QuarkusTest
@TestSecurity(user = "parity-it@lt.test", roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class SurfaceParityIT extends IntegrationTestBase {

    static final String PRIMARY = "parity-it@lt.test";

    static final LocalDate TODAY    = FIXED_TODAY;
    static final LocalDate TOMORROW = FIXED_TODAY.plusDays(1);

    UUID primaryId;
    Action action;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Parity User").id;
        action    = newAction(primaryId, "Running");
    }

    @Test
    void overlongActionName_rejectedOnBothSurfaces_nothingPersisted() {
        final String longName = "x".repeat(101);

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + longName + "\"}")
                .post("/api/v1/actions")
                .then().statusCode(400);

        given().formParam("name", longName)
                .post("/internal/actions")
                .then().statusCode(409); // the HTMX surface reports every validation failure as a conflict banner

        runInTx(() -> assertThat(Action.count("userId = ?1 and name = ?2", primaryId, longName))
            .as("an over-long name must be rejected by BOTH surfaces without persisting")
            .isZero());
    }

    @Test
    void duplicateActionName_rejectedOnBothSurfaces_countUnchanged() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Running"}
                        """)
                .post("/api/v1/actions")
                .then().statusCode(409);

        given().formParam("name", "Running")
                .post("/internal/actions")
                .then().statusCode(409);

        runInTx(() -> assertThat(Action.count("userId = ?1 and name = ?2", primaryId, "Running"))
            .as("a duplicate name must be rejected by BOTH surfaces, leaving the single original")
            .isEqualTo(1));
    }

    @Test
    void futureDateWrite_rejectedOnBothSurfaces_noEntryCreated() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"count":1}
                        """)
                .put("/api/v1/logs/" + TOMORROW + "/" + action.id)
                .then().statusCode(400);

        given().formParam("amount", "1")
                .post("/internal/logs/" + TOMORROW + "/" + action.id + "/increment")
                .then().statusCode(400);

        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TOMORROW))
            .as("a future-date write must be rejected by BOTH surfaces without creating an entry")
            .isNull());
    }

    @Test
    void dailyCap_webSaturates_apiRejects() {
        // The shared write ceiling (MAX_DAILY_COUNT) is identical on both surfaces; only the INPUT contract
        // at that ceiling deliberately differs — the web form saturates an over-cap increment to the cap,
        // whereas the API rejects it (never silently changing the caller's value).
        runInTx(() -> newLog(primaryId, action.id, TODAY, 998));

        given().formParam("amount", "10")
                .post("/internal/logs/" + TODAY + "/" + action.id + "/increment")
                .then().statusCode(200);
        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY).count)
            .as("the HTMX increment must saturate the count at 999")
            .isEqualTo(999));

        runInTx(() -> ActionLog.setCount(primaryId, action.id, TODAY, 998));
        given().contentType(ContentType.JSON)
                .body("""
                        {"amount":10}
                        """)
                .post("/api/v1/logs/" + TODAY + "/" + action.id + "/increment")
                .then().statusCode(400);
        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY).count)
            .as("the API increment must reject the over-cap write, leaving the count unchanged")
            .isEqualTo(998));
    }

    @Test
    void outOfRangePageSize_rejectedOnBothSurfaces_valueUnchanged() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"pageSize":9999}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(400);

        given().formParam("pageSize", "9999")
                .patch("/internal/settings")
                .then().statusCode(422);

        runInTx(() -> assertThat(net.zodac.diurnal.user.User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("an out-of-range page size must be rejected by BOTH surfaces, keeping the previous value")
            .isEqualTo(net.zodac.diurnal.user.UserSettings.DEFAULT_PAGE_SIZE));
    }

    @Test
    void unownedAction_rejectedOnBothSurfaces() {
        final UUID unknown = UUID.randomUUID();

        given().contentType(ContentType.JSON)
                .body("""
                        {"count":1}
                        """)
                .put("/api/v1/logs/" + TODAY + "/" + unknown)
                .then().statusCode(404);

        given().formParam("count", "1")
                .post("/internal/logs/" + TODAY + "/" + unknown + "/set")
                .then().statusCode(404);
    }
}
