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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.StatFieldPref;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestSecurity(user = "stats-it@lt.test", roles = Role.Values.USER)
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
                .body(containsString(">3<")); // current streak = 3
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
                .body(containsString(">8<")); // total count = 5+3
    }

    @Test
    void statsPage_renamedStat_rendersUnderTheCustomCaption() {
        runInTx(() -> {
            final Action action = newAction(primaryId, "Renamed");
            newLog(primaryId, action.id, TODAY, 1);

            final User user = User.findByEmail(PRIMARY).orElseThrow();
            user.statsFields = List.of(new StatFieldPref("current-streak", true, "Days in row"), new StatFieldPref("last-performed", true, null));
            user.persist();
        });

        given().get("/stats")
                .then().statusCode(200)
                .body(containsString("Days in row"))
                .body(not(containsString("Current streak")));
    }

    @Test
    void statsList_pagination_page1ShowsNextWhenMoreThanPageSize() {
        runInTx(() -> {
            for (int i = 1; i <= 11; i++) {
                final Action a = newAction(primaryId, String.format("PaginatedAction%02d", i));
                newLog(primaryId, a.id, TODAY, 1);
            }
        });

        given().queryParam("page", 1).get("/internal/stats/list")
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

        given().queryParam("page", 2).get("/internal/stats/list")
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
    // ── Dashboard stats summary (/internal/stats/summary*) ────────────────────

    @Test
    void summary_showsOnlyTheSelectedDaysActions_orderedByThatDaysCount() {
        runInTx(() -> {
            final Action light = newAction(primaryId, "LightToday");
            newLog(primaryId, light.id, TODAY, 1);
            final Action heavy = newAction(primaryId, "HeavyToday");
            newLog(primaryId, heavy.id, TODAY, 9);
            final Action other = newAction(primaryId, "OtherDayOnly");
            newLog(primaryId, other.id, TODAY.minusDays(1), 5);
        });

        final String card = given().get("/internal/stats/summary/" + TODAY)
            .then().statusCode(200)
            .contentType(containsString("text/html"))
            .body(not(containsString("OtherDayOnly")))
            .extract().asString();

        assertThat(card.indexOf("HeavyToday"))
            .as("the day's most-logged action should be rendered first")
            .isLessThan(card.indexOf("LightToday"));
    }

    @Test
    void summary_capsAtThreeActions() {
        runInTx(() -> {
            for (int count = 1; count <= 4; count++) {
                final Action action = newAction(primaryId, "Capped" + count);
                newLog(primaryId, action.id, TODAY, count);
            }
        });

        given().get("/internal/stats/summary/" + TODAY)
                .then().statusCode(200)
                .body(containsString("Capped4"))
                .body(containsString("Capped3"))
                .body(containsString("Capped2"))
                .body(not(containsString("Capped1")));  // lowest count of the four - dropped by the cap
    }

    @Test
    void summary_dayWithNothingLogged_rendersNoCard() {
        runInTx(() -> {
            final Action action = newAction(primaryId, "Yesterday");
            newLog(primaryId, action.id, TODAY.minusDays(1), 1);
        });

        given().get("/internal/stats/summary/" + TODAY)
                .then().statusCode(200)
                .body(blankOrNullString());   // the partial renders nothing at all on a blank day
    }

    @Test
    void summaryMonth_returnsOneCardPerDayOfTheMonth() {
        runInTx(() -> {
            final Action action = newAction(primaryId, "MonthlyHabit");
            newLog(primaryId, action.id, TODAY, 1);
        });

        given().get("/internal/stats/summary-month/2026-06")
                .then().statusCode(200)
                .contentType(containsString("application/json"))
                .body("size()", equalTo(30))                      // June has 30 days, every one keyed
                .body("'2026-06-15'", containsString("MonthlyHabit"))
                .body("'2026-06-14'", not(containsString("MonthlyHabit"))); // not logged that day
    }

    @Test
    void summaryMonth_invalidMonth_returns400() {
        given().get("/internal/stats/summary-month/not-a-month")
                .then().statusCode(400);
    }
}
