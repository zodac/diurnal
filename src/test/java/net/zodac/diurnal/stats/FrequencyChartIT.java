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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the per-action frequency chart on BOTH surfaces: the Stats page's HTMX fragment
 * ({@code GET /internal/stats/chart/{actionId}}) and its public API twin ({@code GET /api/v1/stats/{actionId}/frequency}), plus the picker feeding
 * the fragment's "Compare to..." control. Both chart surfaces translate the same
 * {@link StatsService#frequency(UUID, UUID, java.util.List, String, String)} result, so the cases here pin that neither of them accepts a window, or
 * a set of actions, that the other rejects.
 */
@QuarkusTest
@TestSecurity(user = "freq-it@lt.test", roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class FrequencyChartIT extends IntegrationTestBase {

    static final String PRIMARY = "freq-it@lt.test";
    static final String OTHER = "freq-other@lt.test";
    static final LocalDate TODAY = FIXED_TODAY;

    UUID primaryId;
    UUID otherId;
    Action action;
    Action second;
    Action third;
    Action fourth;
    Action otherAction;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Frequency User").id;
        otherId = newUser(OTHER, "Other User").id;
        // Named so the name-ascending candidate list has a known order: Cycling, Running, Swimming, Yoga.
        action = newAction(primaryId, "Running");
        second = newAction(primaryId, "Yoga");
        third = newAction(primaryId, "Cycling");
        fourth = newAction(primaryId, "Swimming");
        otherAction = newAction(otherId, "Theirs");
    }

    private static String thisMonthKey() {
        return YearMonth.from(TODAY).toString();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    @Test
    void frequency_defaultWindow_isTheMonthContainingToday() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 4));

        given().get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(200)
                .body("series.size()", equalTo(1))
                .body("series[0].subjectId", equalTo(action.id.toString()))
                .body("series[0].name", equalTo("Running"))
                .body("series[0].total", equalTo(4))
                .body("period", equalTo("month"))
                .body("periodKey", equalTo(thisMonthKey()))
                .body("slots.size()", equalTo(TODAY.lengthOfMonth()))
                .body("total", equalTo(4))
                .body("peak", equalTo(4))
                .body("slots[" + (TODAY.getDayOfMonth() - 1) + "].bars[0].count", equalTo(4));
    }

    @Test
    void frequency_yearWindow_hasOneBarPerMonth() {
        runInTx(() -> {
            newLog(primaryId, action.id, TODAY, 2);
            newLog(primaryId, action.id, TODAY.minusDays(1), 3);
        });

        given().queryParam("period", "year")
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(200)
                .body("period", equalTo("year"))
                .body("periodKey", equalTo(String.valueOf(TODAY.getYear())))
                .body("slots.size()", equalTo(12));
    }

    @Test
    void frequency_explicitWindow_onlyCountsThatWindow() {
        final LocalDate lastMonth = TODAY.minusMonths(1).withDayOfMonth(1);
        runInTx(() -> {
            newLog(primaryId, action.id, TODAY, 5);
            newLog(primaryId, action.id, lastMonth, 7);
        });

        given().queryParam("at", YearMonth.from(lastMonth).toString())
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(200)
                .body("periodKey", equalTo(YearMonth.from(lastMonth).toString()))
                .body("total", equalTo(7))
                .body("slots[0].bars[0].count", equalTo(7));
    }

    @Test
    void frequency_neverLoggedAction_returnsAnEmptyWindow() {
        given().get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(200)
                .body("total", equalTo(0))
                .body("peak", equalTo(0))
                .body("slots.size()", equalTo(TODAY.lengthOfMonth()));
    }

    @Test
    void frequency_unknownPeriod_isRejectedNotCoerced() {
        given().queryParam("period", "week")
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(400)
                .body("message", containsString("week"));
    }

    @Test
    void frequency_malformedWindow_isRejectedNotCoerced() {
        given().queryParam("at", "2026-13")
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(400)
                .body("message", containsString("2026-13"));
    }

    @Test
    void frequency_windowOfTheWrongShapeForThePeriod_isRejected() {
        given().queryParam("period", "year").queryParam("at", "2026-07")
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(400);
    }

    @Test
    void frequency_actionOfAnotherUser_isNotFound() {
        given().get("/api/v1/stats/" + otherAction.id + "/frequency")
                .then().statusCode(404);
    }

    @Test
    void frequency_unknownAction_isNotFound() {
        given().get("/api/v1/stats/" + UUID.randomUUID() + "/frequency")
                .then().statusCode(404);
    }

    // ── Comparing actions ───────────────────────────────────────────────────

    @Test
    void frequency_comparedAction_addsASecondSeriesToEverySlot() {
        runInTx(() -> {
            newLog(primaryId, action.id, TODAY, 4);
            newLog(primaryId, second.id, TODAY, 6);
        });

        given().queryParam("compare", second.id.toString())
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(200)
                .body("series.size()", equalTo(2))
                .body("series[0].subjectId", equalTo(action.id.toString()))
                .body("series[1].subjectId", equalTo(second.id.toString()))
                .body("series[0].total", equalTo(4))
                .body("series[1].total", equalTo(6))
                .body("total", equalTo(10))
                .body("peak", equalTo(6))
                .body("slots[" + (TODAY.getDayOfMonth() - 1) + "].bars.size()", equalTo(2))
                .body("slots[" + (TODAY.getDayOfMonth() - 1) + "].bars[1].count", equalTo(6))
                .body("slots[0].bars.size()", equalTo(2));
    }

    @Test
    void frequency_threeActions_areAllCharted() {
        runInTx(() -> {
            newLog(primaryId, action.id, TODAY, 1);
            newLog(primaryId, second.id, TODAY, 2);
            newLog(primaryId, third.id, TODAY, 3);
        });

        given().queryParam("compare", second.id.toString()).queryParam("compare", third.id.toString())
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(200)
                .body("series.size()", equalTo(3))
                .body("total", equalTo(6));
    }

    @Test
    void frequency_moreThanThreeActions_isRejected() {
        runInTx(() -> {
            newLog(primaryId, second.id, TODAY, 1);
            newLog(primaryId, third.id, TODAY, 1);
            newLog(primaryId, fourth.id, TODAY, 1);
        });

        given().queryParam("compare", second.id.toString())
                .queryParam("compare", third.id.toString())
                .queryParam("compare", fourth.id.toString())
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(400)
                .body("message", containsString("maximum is 3"));
    }

    @Test
    void frequency_repeatedAction_isRejectedNotCollapsed() {
        runInTx(() -> newLog(primaryId, second.id, TODAY, 1));

        given().queryParam("compare", second.id.toString()).queryParam("compare", second.id.toString())
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(400)
                .body("message", containsString("more than once"));
    }

    @Test
    void frequency_actionComparedAgainstItself_isRejected() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 1));

        given().queryParam("compare", action.id.toString())
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(400)
                .body("message", containsString("more than once"));
    }

    @Test
    void frequency_neverLoggedComparison_isRejected() {
        // The picker only ever offers actions with at least one logged entry, so the API rejects the
        // same set rather than drawing a flat series the UI could not have produced.
        given().queryParam("compare", second.id.toString())
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(400)
                .body("message", containsString("never been logged"));
    }

    @Test
    void frequency_comparisonOfAnotherUser_isNotFound() {
        given().queryParam("compare", otherAction.id.toString())
                .get("/api/v1/stats/" + action.id + "/frequency")
                .then().statusCode(404);
    }

    // ── Internal fragment ───────────────────────────────────────────────────

    @Test
    void chartFragment_rendersTheBarsAndTheirHoverValues() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 4));

        given().get("/internal/stats/chart/" + action.id)
                .then().statusCode(200)
                .body(containsString("chart-plot"))
                .body(containsString("data-chart-shown-period=\"month\""))
                .body(containsString("data-chart-shown-at=\"" + thisMonthKey() + "\""))
                .body(containsString("4 times"));
    }

    @Test
    void chartFragment_emptyWindow_rendersTheEmptyState() {
        given().get("/internal/stats/chart/" + action.id)
                .then().statusCode(200)
                .body(containsString("Nothing logged in"));
    }

    @Test
    void chartFragment_singleEntry_isNotPluralised() {
        runInTx(() -> newLog(primaryId, action.id, TODAY, 1));

        given().get("/internal/stats/chart/" + action.id)
                .then().statusCode(200)
                .body(containsString(": 1 time<"));
    }

    @Test
    void chartFragment_unknownPeriod_isRejected() {
        given().queryParam("period", "week")
                .get("/internal/stats/chart/" + action.id)
                .then().statusCode(400);
    }

    @Test
    void chartFragment_malformedWindow_isRejected() {
        given().queryParam("at", "not-a-month")
                .get("/internal/stats/chart/" + action.id)
                .then().statusCode(400);
    }

    @Test
    void chartFragment_actionOfAnotherUser_isNotFound() {
        given().get("/internal/stats/chart/" + otherAction.id)
                .then().statusCode(404);
    }

    @Test
    void chartFragment_comparedActions_areBothInTheLegendAndTheHoverText() {
        runInTx(() -> {
            newLog(primaryId, action.id, TODAY, 4);
            newLog(primaryId, second.id, TODAY, 1);
        });

        given().queryParam("compare", second.id.toString())
                .get("/internal/stats/chart/" + action.id)
                .then().statusCode(200)
                .body(containsString("data-chart-shown-compare=\"" + second.id + "\""))
                .body(containsString("Stop comparing Yoga"))
                .body(containsString("Running: 4 times"))
                .body(containsString("Yoga: 1 time"));
    }

    @Test
    void chartFragment_fullChart_stopsOfferingThePicker() {
        runInTx(() -> {
            newLog(primaryId, second.id, TODAY, 1);
            newLog(primaryId, third.id, TODAY, 1);
        });

        given().queryParam("compare", second.id.toString()).queryParam("compare", third.id.toString())
                .get("/internal/stats/chart/" + action.id)
                .then().statusCode(200)
                .body(not(containsString("Compare to...")));
    }

    @Test
    void chartFragment_roomToCompare_offersThePicker() {
        runInTx(() -> newLog(primaryId, second.id, TODAY, 1));

        given().get("/internal/stats/chart/" + action.id)
                .then().statusCode(200)
                .body(containsString("Compare to..."))
                .body(containsString("chart-compare-panel"));
    }

    // ── Compare picker ──────────────────────────────────────────────────────

    @Test
    void candidates_onlyOffersLoggedActionsThatAreNotAlreadyCharted() {
        runInTx(() -> {
            newLog(primaryId, second.id, TODAY, 1);
            newLog(primaryId, third.id, TODAY, 1);
            // `fourth` is deliberately never logged, and `action` is the graph's own action.
        });

        given().get("/internal/stats/chart/" + action.id + "/candidates")
                .then().statusCode(200)
                .body(containsString("Cycling"))
                .body(containsString("Yoga"))
                .body(not(containsString("Swimming")))
                .body(not(containsString("Running")));
    }

    @Test
    void candidates_excludesActionsAlreadyBeingCompared() {
        runInTx(() -> {
            newLog(primaryId, second.id, TODAY, 1);
            newLog(primaryId, third.id, TODAY, 1);
        });

        given().queryParam("compare", third.id.toString())
                .get("/internal/stats/chart/" + action.id + "/candidates")
                .then().statusCode(200)
                .body(containsString("Yoga"))
                .body(not(containsString("Cycling")));
    }

    @Test
    void candidates_filtersByNameCaseInsensitively() {
        runInTx(() -> {
            newLog(primaryId, second.id, TODAY, 1);
            newLog(primaryId, third.id, TODAY, 1);
        });

        given().queryParam("q", "yo")
                .get("/internal/stats/chart/" + action.id + "/candidates")
                .then().statusCode(200)
                .body(containsString("Yoga"))
                .body(not(containsString("Cycling")));
    }

    @Test
    void candidates_noneEligible_rendersTheEmptyState() {
        given().get("/internal/stats/chart/" + action.id + "/candidates")
                .then().statusCode(200)
                .body(containsString("Nothing else to compare."));
    }

    @Test
    void candidates_neverOffersAnotherUsersAction() {
        runInTx(() -> newLog(otherId, otherAction.id, TODAY, 1));

        given().get("/internal/stats/chart/" + action.id + "/candidates")
                .then().statusCode(200)
                .body(not(containsString("Theirs")));
    }
}
