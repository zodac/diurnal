package dev.lifetracker.web;

import dev.lifetracker.IntegrationTestBase;
import dev.lifetracker.user.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

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
    void logout_clearsCookieAndRedirectsToLogin() {
        given().redirects().follow(false)
                .post("/logout")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsString("/login"))
                // lt_session cookie should be cleared (maxAge=0 or empty value)
                .cookie("lt_session", anyOf(emptyOrNullString(), equalTo("")));
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
                .body(containsString("web-it@lt.test"));
    }
}
