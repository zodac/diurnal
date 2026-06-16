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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "stats-it@lt.test", roles = "user")
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class StatsResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "stats-it@lt.test";
    static final LocalDate TODAY = FIXED_TODAY;

    UUID primaryId;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Stats User").id;
    }

    // ── Stats page ────────────────────────────────────────────────────────────

    @Test
    void statsPage_noLoggedActions_hasActionsIsFalse() {
        // Create an action but don't log it — it has no data so the stats page
        // should show the empty-state message. hasActions refers to actions with logs.
        runInTx(() -> newAction(primaryId, "Unlogged"));

        given().get("/stats")
                .then().statusCode(200)
                .body(containsString("No logs for any actions yet."));
    }

    @Test
    void statsPage_withLoggedAction_showsActionName() {
        runInTx(() -> {
            final Action action = newAction(primaryId, "Jogging");
            newLog(primaryId, action.id, TODAY, 2);
        });

        given().get("/stats")
                .then().statusCode(200)
                .body(containsString("Jogging"));
    }

    @Test
    void statsPage_streakDisplayed() {
        runInTx(() -> {
            final Action action = newAction(primaryId, "Streaker");
            newLog(primaryId, action.id, TODAY, 1);
            newLog(primaryId, action.id, TODAY.minusDays(1), 1);
            newLog(primaryId, action.id, TODAY.minusDays(2), 1);
        });

        given().get("/stats")
                .then().statusCode(200)
                .body(containsString("3")); // current streak = 3
    }

    @Test
    void statsPage_totalCountDisplayed() {
        runInTx(() -> {
            final Action action = newAction(primaryId, "Counter");
            newLog(primaryId, action.id, TODAY, 5);
            newLog(primaryId, action.id, TODAY.minusDays(1), 3);
        });

        given().get("/stats")
                .then().statusCode(200)
                .body(containsString("8")); // total count = 5+3
    }

    @Test
    void statsList_pagination_page1ShowsNextWhenMoreThanPageSize() {
        runInTx(() -> {
            for (int i = 1; i <= 11; i++) {
                final Action a = newAction(primaryId, String.format("PaginatedAction%02d", i));
                newLog(primaryId, a.id, TODAY, 1);
            }
        });

        given().queryParam("page", 1).get("/stats/list")
                .then().statusCode(200)
                .body(containsString("Next"));
    }

    @Test
    void statsList_pagination_page2ShowsPrevious() {
        runInTx(() -> {
            for (int i = 1; i <= 11; i++) {
                final Action a = newAction(primaryId, String.format("PageAction%02d", i));
                newLog(primaryId, a.id, TODAY, 1);
            }
        });

        given().queryParam("page", 2).get("/stats/list")
                .then().statusCode(200)
                .body(containsString("Previous"));
    }

    @Test
    void statsPage_actionsWithNoLogsAreHidden() {
        runInTx(() -> {
            newAction(primaryId, "NeverLogged");
            final Action logged = newAction(primaryId, "Logged");
            newLog(primaryId, logged.id, TODAY, 1);
        });

        given().get("/stats")
                .then().statusCode(200)
                .body(containsString("Logged"))
                .body(not(containsString("NeverLogged")));
    }
}
