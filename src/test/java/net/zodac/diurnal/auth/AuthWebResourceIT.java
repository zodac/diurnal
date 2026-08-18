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

package net.zodac.diurnal.auth;

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.BAD_REQUEST;
import static net.zodac.diurnal.http.HttpStatusCodes.FOUND;
import static net.zodac.diurnal.http.HttpStatusCodes.MOVED_PERMANENTLY;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static net.zodac.diurnal.http.HttpStatusCodes.SEE_OTHER;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuthWebResourceIT extends IntegrationTestBase {

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
                .then().statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND)))
                .header("Location", containsString("/login"));
    }

    @Test
    void stats_unauthenticated_redirectsToLogin() {
        given().redirects().follow(false)
                .get("/stats")
                .then().statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND)))
                .header("Location", containsString("/login"));
    }

    @Test
    void actions_unauthenticated_redirectsToLogin() {
        given().redirects().follow(false)
                .get("/actions")
                .then().statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND)))
                .header("Location", containsString("/login"));
    }

    // ── Login page ────────────────────────────────────────────────────────────

    @Test
    void loginPage_returnsHtml() {
        given().get("/login")
                .then().statusCode(OK)
                .contentType(containsString("text/html"));
    }

    @Test
    void loginPage_withErrorParam_showsErrorIndicator() {
        given().queryParam("error", "true").get("/login")
                .then().statusCode(OK)
                .body(anyOf(containsString("error"), containsString("invalid"), containsString("Invalid")));
    }

    @Test
    void loginPage_withRegisteredParam_showsSuccessIndicator() {
        given().queryParam("registered", "true").get("/login")
                .then().statusCode(OK)
                .body(containsString("Account created"));
    }

    @Test
    void loginPage_withOidcErrorParam_showsOidcErrorBanner() {
        given().queryParam("error", "oidc").get("/login")
                .then().statusCode(OK)
                .body(containsString("not authorized"));
    }

    @Test
    void loginPage_withLockoutCookie_showsLockoutBannerAndSeedsCountdown() {
        // Cookie value = seconds left on the lockout (900 = 15 minutes).
        given().cookie("diurnal_login_lockout", "900").get("/login")
                .then().statusCode(OK)
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
            .statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND), equalTo(SEE_OTHER)))
            .header("Location", allOf(
            containsString("error=oidc"),
            not(containsString("access_denied"))));
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void registerPage_returnsHtml() {
        given().get("/register")
                .then().statusCode(OK)
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
                .statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND), equalTo(SEE_OTHER)))
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
                .statusCode(BAD_REQUEST)
                .body(containsString("Passwords do not match"));
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
                .statusCode(BAD_REQUEST)
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
                .statusCode(BAD_REQUEST)
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
                .statusCode(BAD_REQUEST)
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
                .statusCode(BAD_REQUEST)
                .body(containsString("Email must contain an @ symbol."));
    }

    @Test
    void register_singleCharacterDisplayName_rendersErrorBannerInPage() {
        // The 2-100 rule comes from the shared RegistrationService, so the web form enforces the same
        // display-name bounds the API always documented.
        given().redirects().follow(false)
                .formParam("email", "shortname@example.com")
                .formParam("displayName", "A")
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(BAD_REQUEST)
                .body(containsString("Display name must be between 2 and 50 characters."));
    }

    @Test
    void register_overlongDisplayName_rendersErrorBannerInPage() {
        given().redirects().follow(false)
                .formParam("email", "longname@example.com")
                .formParam("displayName", "x".repeat(101))
                .formParam("password", "password123")
                .formParam("confirmPassword", "password123")
                .post("/register")
                .then()
                .statusCode(BAD_REQUEST)
                .body(containsString("Display name must be between 2 and 50 characters."));
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
                .statusCode(BAD_REQUEST)
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
                .statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND), equalTo(SEE_OTHER)))
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
                .statusCode(BAD_REQUEST)
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
                .statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND), equalTo(SEE_OTHER)))
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
                .statusCode(anyOf(equalTo(MOVED_PERMANENTLY), equalTo(FOUND), equalTo(SEE_OTHER)))
                .cookie("q_session", anyOf(emptyOrNullString(), equalTo("")));
    }

    // ── Dashboard (authenticated) ──────────────────────────────────────────────
}
