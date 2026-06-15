package net.zodac.diurnal.web;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import net.zodac.diurnal.IntegrationTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
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
    void register_validData_redirectsToLoginWithRegisteredParam() {
        given().redirects().follow(false)
                .formParam("email", "newweb@example.com")
                .formParam("displayName", "New Web User")
                .formParam("password", "password123")
                .post("/register")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/login?registered"));
    }

    @Test
    void register_duplicateEmail_redirectsWithEmailTakenError() {
        // "taken@example.com" is pre-created in createDbState()
        given().redirects().follow(false)
                .formParam("email", "taken@example.com")
                .formParam("displayName", "Dup")
                .formParam("password", "password123")
                .post("/register")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("email_taken"));
    }

    @Test
    void register_passwordTooShort_redirectsWithInvalidError() {
        given().redirects().follow(false)
                .formParam("email", "shortpw@example.com")
                .formParam("displayName", "Short PW")
                .formParam("password", "short")
                .post("/register")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("invalid"));
    }

    @Test
    void register_blankDisplayName_redirectsWithInvalidError() {
        given().redirects().follow(false)
                .formParam("email", "nodisplay@example.com")
                .formParam("displayName", "  ")
                .formParam("password", "password123")
                .post("/register")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("invalid"));
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
    void settingsPage_authenticated_returns200() {
        given().get("/settings")
                .then().statusCode(200)
                .body(containsString("web-it@lt.test"))
                // Timezone picker renders: server default (UTC in the test profile) first as a plain,
                // pre-selected "UTC" option, plus curated zones labelled with their current UTC offset.
                .body(containsString("<option value=\"\" selected>UTC</option>"))
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
}
