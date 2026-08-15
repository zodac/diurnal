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

package net.zodac.diurnal.web;

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.FOUND;
import static net.zodac.diurnal.http.HttpStatusCodes.MOVED_PERMANENTLY;
import static net.zodac.diurnal.http.HttpStatusCodes.NOT_FOUND;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static net.zodac.diurnal.http.HttpStatusCodes.SEE_OTHER;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the dashboard served by {@link DashboardWebResource} - the one page belonging to no single feature - and for the shared
 * error pages.
 */
@QuarkusTest
class DashboardWebResourceIT extends IntegrationTestBase {

    @Override
    protected void createDbState() {
        newUser("web-it@lt.test", "Web User");
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void dashboard_authenticated_returns200() {
        // "web-it@lt.test" is pre-created in createDbState()
        given().get("/")
                .then().statusCode(OK)
                .contentType(containsString("text/html"))
                .body(containsString("Web User"));
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void dashboard_withLoggedAction_showsTopThreeEnabledStatTiles() {
        // Seed an action logged TODAY (the day the dashboard renders for) so the stats-summary card
        // renders. With the default (never-customised) "Action stats" preference, the top three enabled
        // fields are the first three declared: Last performed, First performed, Current streak — and NOT
        // any lower-ranked field (e.g. Total count), confirming the summary honours the Statistics setting
        // rather than a fixed trio.
        runInTx(() -> {
            final UUID userId = User.findByEmail("web-it@lt.test").orElseThrow().id;
            final Action action = newAction(userId, "Meditate");
            newLog(userId, action.id, FIXED_TODAY, 1);
        });

        given().get("/")
            .then().statusCode(OK)
            .body(containsString("Meditate"))
            .body(allOf(
            containsString("Last performed"),
            containsString("First performed"),
            containsString("Current streak"),
            not(containsString("Total count"))));
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void dashboard_summaryShowsActionsLoggedOnTheRenderedDayOnly() {
        // The dashboard summary strip is the "top actions on the selected day" path, and the page renders
        // for today: an action logged only on another day must not appear, while one logged today must.
        runInTx(() -> {
            final UUID userId = User.findByEmail("web-it@lt.test").orElseThrow().id;
            final Action current = newAction(userId, "LoggedTodayHabit");
            newLog(userId, current.id, FIXED_TODAY, 1);
            final Action stale = newAction(userId, "LoggedYesterdayHabit");
            newLog(userId, stale.id, FIXED_TODAY.minusDays(1), 1);
        });

        given().get("/")
                .then().statusCode(OK)
                .body(containsString("LoggedTodayHabit"))
                .body(not(containsString("LoggedYesterdayHabit")));
    }


    // ── 404 page ──────────────────────────────────────────────────────────────

    @Test
    void unknownPath_unauthenticated_returns404WithErrorPage() {
        given().get("/this-path-does-not-exist")
                .then().statusCode(NOT_FOUND)
                .contentType(containsString("text/html"))
                .body(containsString("Page Not Found"));
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = Role.Values.USER_INTERNAL_VALUE)
    void unknownPath_authenticated_returns404WithErrorPage() {
        given().get("/this-path-does-not-exist")
                .then().statusCode(NOT_FOUND)
                .contentType(containsString("text/html"))
                .body(containsString("Page Not Found"));
    }

    @Test
    void unknownPath_unauthenticatedBrowser_redirectsToLogin() {
        // A browser navigation (Accept: text/html) to an unknown route, while signed out, is sent to
        // the login flow rather than shown a 404. Users exist here, so /login does not bounce onward.
        given().redirects().follow(false)
                .header("Accept", "text/html")
                .get("/this-path-does-not-exist")
                .then()
                .statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND), equalTo(SEE_OTHER)))
                .header("Location", containsString("/login"));
    }

    @Test
    void unknownApiPath_browser_returns404NotRedirect() {
        // /api 404s must never be redirected into the web auth flow, even with an HTML Accept header.
        given().redirects().follow(false)
                .header("Accept", "text/html")
                .get("/api/this-does-not-exist")
                .then()
                .statusCode(NOT_FOUND);
    }
}
