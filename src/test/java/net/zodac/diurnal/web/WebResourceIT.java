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
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

@QuarkusTest
class WebResourceIT extends IntegrationTestBase {

    @Override
    protected void createDbState() {
        // Used by the two authenticated method-level @TestSecurity tests
        newUser("web-it@lt.test", "Web User");
        // Used by the duplicate-email registration test
        newUser("taken@example.com", "Taken");
    }

    // ── Unauthenticated redirects ──────────────────────────────────────────────

    @Test
    void dashboard_unauthenticated_redirectsToLogin() {
        given().redirects().follow(false)
                .get("/")
                .then().statusCode(anyOf(equalTo(301), equalTo(302)))
                .header("Location", containsString("/login"));
    }

    @Test
    void stats_unauthenticated_redirectsToLogin() {
        given().redirects().follow(false)
                .get("/stats")
                .then().statusCode(anyOf(equalTo(301), equalTo(302)))
                .header("Location", containsString("/login"));
    }

    @Test
    void actions_unauthenticated_redirectsToLogin() {
        given().redirects().follow(false)
                .get("/actions")
                .then().statusCode(anyOf(equalTo(301), equalTo(302)))
                .header("Location", containsString("/login"));
    }

    // ── Login page ────────────────────────────────────────────────────────────

    @Test
    void loginPage_returnsHtml() {
        given().get("/login")
                .then().statusCode(200)
                .contentType(containsString("text/html"));
    }

    @Test
    void loginPage_withErrorParam_showsErrorIndicator() {
        given().queryParam("error", "true").get("/login")
                .then().statusCode(200)
                .body(anyOf(containsString("error"), containsString("invalid"), containsString("Invalid")));
    }

    @Test
    void loginPage_withRegisteredParam_showsSuccessIndicator() {
        given().queryParam("registered", "true").get("/login")
                .then().statusCode(200)
                .body(containsString("Account created"));
    }

    @Test
    void loginPage_withOidcErrorParam_showsOidcErrorBanner() {
        given().queryParam("error", "oidc").get("/login")
                .then().statusCode(200)
                .body(containsString("not authorized"));
    }

    @Test
    void loginPage_withLockoutCookie_showsLockoutBannerAndSeedsCountdown() {
        // Cookie value = seconds left on the lockout (900 = 15 minutes).
        given().cookie("diurnal_login_lockout", "900").get("/login")
                .then().statusCode(200)
                .body(containsString("Too many failed attempts"))
                // The no-JS banner states the exact remaining seconds.
                .body(containsString("900 seconds"))
                // app.js reads the seconds-left from this header to run the live countdown.
                .header("X-Lockout-Retry-After", equalTo("900"));
    }

    @Test
    void loginPage_withMessyOidcErrorParam_redirectsToCleanUrl() {
        // Authelia appends its own ?error=... to the error-path, creating a double-? URL.
        // The handler detects error starting with "oidc" (but not exactly "oidc") and redirects.
        given().redirects().follow(false)
                .get("/login?error=oidc%3Ferror%3Daccess_denied%26error_description%3DSomething")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", allOf(
                        containsString("error=oidc"),
                        not(containsString("access_denied"))));
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void registerPage_returnsHtml() {
        given().get("/register")
                .then().statusCode(200)
                .contentType(containsString("text/html"));
    }

    @Test
    void register_validData_logsInAndRedirectsToDashboard() {
        given().redirects().follow(false)
                .formParam("email", "newweb@example.com")
                .formParam("displayName", "New Web User")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                // Registration logs the new account straight in: a session cookie is set and the
                // browser is sent to the dashboard, not back to the login page.
                .cookie("diurnal_session", not(emptyOrNullString()))
                .header("Location", not(containsString("/login")));
    }

    @Test
    void register_mismatchedConfirmPassword_rendersErrorBannerInPage() {
        given().redirects().follow(false)
                .formParam("email", "mismatch@example.com")
                .formParam("displayName", "Mismatch")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password124")
                .post("/register")
                .then()
                .statusCode(400)
                .body(containsString("The passwords did not match."));
    }

    @Test
    void register_duplicateEmail_rendersErrorBannerInPage() {
        // "taken@example.com" is pre-created in createDbState()
        given().redirects().follow(false)
                .formParam("email", "taken@example.com")
                .formParam("displayName", "Dup")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(400)
                .body(containsString("That email is already registered."));
    }

    @Test
    void register_failure_preservesSubmittedFieldValues() {
        // A failed submission must re-render the form with the user's non-secret input intact (not
        // cleared) — but the password must NEVER be re-echoed back into the HTML.
        given().redirects().follow(false)
                .formParam("email", "taken@example.com")
                .formParam("displayName", "Dup Name")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(400)
                .body(containsString("value=\"taken@example.com\""))
                .body(containsString("value=\"Dup Name\""))
                .body(not(containsString("value=\"password123\"")));
    }

    @Test
    void register_emptyFields_rendersBannerListingEachMissingField() {
        given().redirects().follow(false)
                .formParam("email", "")
                .formParam("displayName", "")
                .formParam("password", "")
                .formParam("confirmPassword", "")
                .post("/register")
                .then()
                .statusCode(400)
                // Multiple missing fields → plural heading + each field on its own list item.
                .body(containsString("Please fill in the following fields:"))
                .body(containsString("<li>Email</li>"))
                .body(containsString("<li>Display name</li>"))
                .body(containsString("<li>Password</li>"))
                .body(containsString("<li>Confirm password</li>"));
    }

    @Test
    void register_emailWithoutAtSign_rendersErrorBannerInPage() {
        given().redirects().follow(false)
                .formParam("email", "no-at-sign")
                .formParam("displayName", "No At")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(400)
                .body(containsString("Email must contain an @ symbol."));
    }

    @Test
    void register_passwordTooLong_rendersErrorBannerInPage() {
        final String tooLong = "a".repeat(129);
        given().redirects().follow(false)
                .formParam("email", "longpw@example.com")
                .formParam("displayName", "Long PW")
                .formParam("password", tooLong)
                .formParam("confirmPassword", tooLong)
                .post("/register")
                .then()
                .statusCode(400)
                .body(containsString("Password must be at most 128 characters."));
    }

    @Test
    void register_shortPassword_succeeds() {
        // No minimum length: a short (but non-empty) password is accepted.
        given().redirects().follow(false)
                .formParam("email", "shortpw@example.com")
                .formParam("displayName", "Short PW")
                .formParam("password", "short")
                .formParam("confirmPassword", "short")
                .post("/register")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .cookie("diurnal_session", not(emptyOrNullString()))
                .header("Location", not(containsString("/login")));
    }

    @Test
    void register_blankDisplayName_rendersErrorBannerInPage() {
        given().redirects().follow(false)
                .formParam("email", "nodisplay@example.com")
                .formParam("displayName", "  ")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(400)
                // A single missing field → singular heading + one list item.
                .body(containsString("Please fill in the following field:"))
                .body(containsString("<li>Display name</li>"));
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_withoutOidcSession_redirectsToLogin() {
        // No q_session cookie → password-auth user, no IdP session to terminate.
        // Must always redirect to /login regardless of OIDC_LOGOUT_URL env var.
        given().redirects().follow(false)
                .post("/logout")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/login"))
                .cookie("diurnal_session", anyOf(emptyOrNullString(), equalTo("")));
    }

    @Test
    void logout_withOidcSession_clearsSessionCookieAndRedirects() {
        // With a q_session cookie, logout redirects to OIDC_LOGOUT_URL (if configured) or /login.
        // The redirect target is environment-specific, so we assert only that the redirect happens
        // and the OIDC session cookie is cleared.
        given().redirects().follow(false)
                .cookie("q_session", "fake-oidc-session-token")
                .post("/logout")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .cookie("q_session", anyOf(emptyOrNullString(), equalTo("")));
    }

    // ── Dashboard (authenticated) ──────────────────────────────────────────────

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = "user")
    void dashboard_authenticated_returns200() {
        // "web-it@lt.test" is pre-created in createDbState()
        given().get("/")
                .then().statusCode(200)
                .contentType(containsString("text/html"))
                .body(containsString("Web User"));
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = "user")
    void dashboard_withLoggedAction_showsTopThreeEnabledStatTiles() {
        // Seed a logged action so the stats-summary card renders. With the default (never-customised)
        // "Action stats" preference, the top three enabled fields are the first three declared:
        // Current streak, Longest streak, Biggest gap — and NOT any lower-ranked field (e.g. Total
        // count), confirming the summary now honours the Statistics setting rather than a fixed trio.
        runInTx(() -> {
            final UUID userId = User.findByEmail("web-it@lt.test").orElseThrow().id;
            final Action action = newAction(userId, "Meditate");
            newLog(userId, action.id, FIXED_TODAY, 1);
        });

        given().get("/")
                .then().statusCode(200)
                .body(containsString("Meditate"))
                .body(allOf(
                        containsString("Current streak"),
                        containsString("Longest streak"),
                        containsString("Biggest gap"),
                        not(containsString("Total count"))));
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = "user")
    void dashboard_summaryShowsThisMonthActionAndHidesLastMonthOnlyAction() {
        // The dashboard summary strip is the "3 most recent actions this month" path: an action logged
        // only in a previous month must not appear, while one logged this month must.
        runInTx(() -> {
            final UUID userId = User.findByEmail("web-it@lt.test").orElseThrow().id;
            final Action current = newAction(userId, "CurrentMonthHabit");
            newLog(userId, current.id, FIXED_TODAY, 1);
            final Action stale = newAction(userId, "LastMonthHabit");
            newLog(userId, stale.id, FIXED_TODAY.minusMonths(1), 1);
        });

        given().get("/")
                .then().statusCode(200)
                .body(containsString("CurrentMonthHabit"))
                .body(not(containsString("LastMonthHabit")));
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = "user")
    void settingsPage_authenticated_returns200() {
        given().get("/settings")
                .then().statusCode(200)
                .body(containsString("web-it@lt.test"))
                // Timezone picker renders every curated zone alphabetically, each labelled with its
                // current UTC offset. A new user (no override) defaults to the server zone (UTC in
                // the test profile), so its own option is pre-selected.
                .body(containsString("<option value=\"UTC\" selected>UTC</option>"))
                .body(containsString("Pacific/Auckland (UTC+12)"));
    }

    // ── 404 page ──────────────────────────────────────────────────────────────

    @Test
    void unknownPath_unauthenticated_returns404WithErrorPage() {
        given().get("/this-path-does-not-exist")
                .then().statusCode(404)
                .contentType(containsString("text/html"))
                .body(containsString("Page Not Found"));
    }

    @Test
    @TestSecurity(user = "web-it@lt.test", roles = "user")
    void unknownPath_authenticated_returns404WithErrorPage() {
        given().get("/this-path-does-not-exist")
                .then().statusCode(404)
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
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/login"));
    }

    @Test
    void unknownApiPath_browser_returns404NotRedirect() {
        // /api 404s must never be redirected into the web auth flow, even with an HTML Accept header.
        given().redirects().follow(false)
                .header("Accept", "text/html")
                .get("/api/this-does-not-exist")
                .then()
                .statusCode(404);
    }
}
