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

package net.zodac.diurnal.stats;

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

/**
 * Integration tests for the public stats API ({@code GET /api/v1/stats}): per-action statistics for logged actions only, computed on the frozen
 * clock, isolated per user.
 */
@QuarkusTest
@TestSecurity(user = "stats-api-it@lt.test", roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class StatsApiResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "stats-api-it@lt.test";
    static final String OTHER   = "stats-api-other@lt.test";

    static final LocalDate TODAY = FIXED_TODAY;

    UUID primaryId;
    UUID otherId;
    Action action;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Stats API User").id;
        otherId   = newUser(OTHER,   "Other User").id;
        action    = newAction(primaryId, "Running");
    }

    @Test
    void stats_noLoggedActions_returnsEmptyArray() {
        given().get("/api/v1/stats")
                .then().statusCode(200)
                .body("items.size()", equalTo(0))
                .body("totalCount", equalTo(0));
    }

    @Test
    void stats_loggedAction_returnsComputedFields() {
        runInTx(() -> {
            newLog(primaryId, action.id, TODAY, 2);
            newLog(primaryId, action.id, TODAY.minusDays(1), 3);
        });

        given().get("/api/v1/stats")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].actionId", equalTo(action.id.toString()))
                .body("items[0].name", equalTo("Running"))
                .body("items[0].totalDays", equalTo(2))
                .body("items[0].totalCount", equalTo(5))
                .body("items[0].currentStreak", equalTo(2))
                .body("items[0].longestStreak", equalTo(2))
                .body("items[0].firstPerformed", equalTo(TODAY.minusDays(1).toString()))
                .body("items[0].lastPerformed", equalTo(TODAY.toString()));
    }

    @Test
    void stats_unloggedActionIsOmitted() {
        runInTx(() -> {
            newAction(primaryId, "Never Logged");
            newLog(primaryId, action.id, TODAY, 1);
        });

        given().get("/api/v1/stats")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].name", equalTo("Running"));
    }

    @Test
    void stats_onlyCurrentUsersActions() {
        runInTx(() -> {
            final Action otherAction = newAction(otherId, "Cycling");
            newLog(primaryId, action.id, TODAY, 1);
            newLog(otherId, otherAction.id, TODAY, 9);
        });

        given().get("/api/v1/stats")
                .then().statusCode(200)
                .body("items.size()", equalTo(1))
                .body("items[0].name", equalTo("Running"));
    }
}
