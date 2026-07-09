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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuthResourceIT extends IntegrationTestBase {

    // AuthResourceIT creates its own users — base setUp() just wipes the tables.

    // The instant AppClock is frozen at by IntegrationTestBase.setUp() (FIXED_TODAY, UTC midnight),
    // so throttle bookkeeping made during a request can be asserted at the same instant.
    private static final Instant FROZEN_NOW = FIXED_TODAY.atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final int MAX_ATTEMPTS = 5;

    @Inject
    LoginThrottles loginThrottles;

    // LoginThrottles is @ApplicationScoped, so its in-memory state survives across tests — wipe it.
    @BeforeEach
    void clearThrottle() {
        loginThrottles.clear();
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void register_validRequest_returns201WithToken() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"new@example.com","displayName":"New User","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then()
                .statusCode(201)
                .body("token", not(emptyOrNullString()))
                .body("email", equalTo("new@example.com"))
                .body("displayName", equalTo("New User"));
    }

    @Test
    void register_normalisesEmailToLowercase() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"UPPER@Example.COM","displayName":"Cased","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then()
                .statusCode(201)
                .body("email", equalTo("upper@example.com"));
    }

    @Test
    void register_duplicateEmail_returns409() {
        final String body = """
                {"email":"dup@example.com","displayName":"First","password":"password1"}
                """;
        given().contentType(ContentType.JSON).body(body).post("/api/auth/register")
                .then().statusCode(201);

        given().contentType(ContentType.JSON).body(body).post("/api/auth/register")
                .then().statusCode(409)
                .body("message", containsStringIgnoringCase("already registered"));
    }

    @Test
    void register_duplicateEmail_caseInsensitive() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"Case@Example.com","displayName":"First","password":"password1"}
                        """)
                .post("/api/auth/register").then().statusCode(201);

        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"case@example.com","displayName":"Second","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then().statusCode(409);
    }

    @Test
    void register_blankEmail_returns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"","displayName":"User","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then().statusCode(400);
    }

    @Test
    void register_blankDisplayName_returns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"valid@example.com","displayName":"","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then().statusCode(400);
    }

    @Test
    void register_blankPassword_returns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"blankpw@example.com","displayName":"User","password":""}
                        """)
                .post("/api/auth/register")
                .then().statusCode(400);
    }

    @Test
    void register_passwordTooLong_returns400() {
        given().contentType(ContentType.JSON)
                .body(String.format(
                        "{\"email\":\"longpw@example.com\",\"displayName\":\"User\",\"password\":\"%s\"}",
                        "a".repeat(129)))
                .post("/api/auth/register")
                .then().statusCode(400);
    }

    @Test
    void register_shortPassword_returns201() {
        // No minimum length: a short (but non-empty) password is accepted.
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"short@example.com","displayName":"User","password":"short"}
                        """)
                .post("/api/auth/register")
                .then().statusCode(201);
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void login_validCredentials_returns200WithToken() {
        registerUser("login@example.com", "Login User", "password123");

        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"login@example.com","password":"password123"}
                        """)
                .post("/api/auth/login")
                .then()
                .statusCode(200)
                .body("token", not(emptyOrNullString()))
                .body("email", equalTo("login@example.com"));
    }

    @Test
    void login_hashWithOutdatedParameters_isRehashedToCurrentCostOnSuccess() {
        // Seed a user whose Argon2id hash uses a higher memory cost than the current configuration,
        // mimicking an account whose hash predates a cost retune.
        runInTx(() -> {
            final User user = newUser("outdated@example.com", "Outdated User");
            user.passwordHash = Argon2Function.getInstance(2048, 1, 1, 32, Argon2.ID)
                    .hash(TEST_PASSWORD).getResult();
        });
        runInTx(() -> assertThat(User.findByEmail("outdated@example.com").orElseThrow().passwordHash)
            .as("seeded account should carry the outdated memory cost")
            .contains("m=2048"));

        given().contentType(ContentType.JSON)
                .body("{\"email\":\"outdated@example.com\",\"password\":\"" + TEST_PASSWORD + "\"}")
                .post("/api/auth/login")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail("outdated@example.com").orElseThrow().passwordHash)
            .as("a successful login should transparently re-hash to the current Argon2id parameters")
            .startsWith("$argon2id$")
            .doesNotContain("m=2048"));
    }

    @Test
    void login_wrongPassword_returns401() {
        registerUser("wrongpw@example.com", "WrongPW", "correct_password");

        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"wrongpw@example.com","password":"wrong_password"}
                        """)
                .post("/api/auth/login")
                .then().statusCode(401);
    }

    @Test
    void login_unknownEmail_returns401() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"nobody@example.com","password":"password123"}
                        """)
                .post("/api/auth/login")
                .then().statusCode(401);
    }

    @Test
    void login_caseInsensitiveEmail() {
        registerUser("CasedLogin@Example.com", "Cased", "password123");

        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"casedlogin@example.com","password":"password123"}
                        """)
                .post("/api/auth/login")
                .then().statusCode(200)
                .body("token", not(emptyOrNullString()));
    }

    @Test
    void login_returnsOpaqueSessionTokenThatAuthenticates() {
        registerUser("session@example.com", "Session User", "password123");

        final String token = given().contentType(ContentType.JSON)
                .body("""
                        {"email":"session@example.com","password":"password123"}
                        """)
                .post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("token");

        assertThat(token)
            .as("The session token must be an opaque string, not a dotted JWT")
            .isNotBlank()
            .doesNotContain(".");

        // The opaque token must authenticate a protected API call as a Bearer credential.
        given().header("Authorization", "Bearer " + token)
                .get("/api/users/me")
                .then().statusCode(200)
                .body("email", equalTo("session@example.com"));
    }

    // ── Per-account throttling ──────────────────────────────────────────────────

    @Test
    void login_afterMaxFailures_returns429WithHumanReadableMessage() {
        registerUser("throttle@example.com", "Throttle", "correct_password");

        // MAX_ATTEMPTS failures are ordinary 401s; they trip the lock but do not themselves block.
        driveToLockout("throttle@example.com");

        // The first attempt made WHILE locked reports the lockout, with its human-readable duration.
        postLogin("throttle@example.com", "wrong_password")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                // States the policy and its duration; discloses nothing about account existence.
                .body("message", containsStringIgnoringCase("too many failed login attempts"))
                .body("message", containsStringIgnoringCase("5 minutes"));
    }

    @Test
    void login_lockedAccount_rejectsEvenTheCorrectPassword() {
        registerUser("locked@example.com", "Locked", "correct_password");

        driveToLockout("locked@example.com");

        // Correct credentials, but the lockout ignores validity while active.
        postLogin("locked@example.com", "correct_password")
                .then().statusCode(429);
    }

    @Test
    void login_afterLockoutWindowElapses_succeeds() {
        registerUser("expiry@example.com", "Expiry", "correct_password");

        driveToLockout("expiry@example.com");
        // Locked: even the correct password is refused.
        postLogin("expiry@example.com", "correct_password").then().statusCode(429);

        // Default account lockout is 5 minutes; advance the clock past it and the account works again.
        freezeInstant(FROZEN_NOW.plus(Duration.ofMinutes(6)), ZoneId.of("UTC"));

        postLogin("expiry@example.com", "correct_password")
                .then().statusCode(200)
                .body("token", not(emptyOrNullString()));
    }

    @Test
    void login_successBeforeThreshold_clearsFailureCount() {
        registerUser("reset@example.com", "Reset", "correct_password");

        // Four failures (below the threshold of five) then a success must reset the tally, so a
        // subsequent failure does not tip an already-primed counter over the edge.
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            postLogin("reset@example.com", "wrong_password").then().statusCode(401);
        }
        postLogin("reset@example.com", "correct_password").then().statusCode(200);
        postLogin("reset@example.com", "wrong_password").then().statusCode(401);

        assertThat(loginThrottles.account().isLocked("reset@example.com", FROZEN_NOW))
                .as("A success must reset the failure count, so one later failure cannot lock the account")
                .isFalse();
    }

    @Test
    void formLogin_belowThreshold_setsNoLockoutCookie() {
        registerUser("formgeneric@example.com", "Form Generic", "correct_password");

        // A single failure is below the threshold — the generic error redirect, no lockout cookie.
        final io.restassured.response.Response response = postFormLoginWrongPassword("formgeneric@example.com");
        response.then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .header("Location", containsStringIgnoringCase("error=true"));
        assertThat(response.getCookie("diurnal_login_lockout"))
                .as("A sub-threshold failure must not set the lockout cookie")
                .isNull();
    }

    @Test
    void formLogin_whileLocked_setsLockoutCookie() {
        registerUser("formthrottle@example.com", "Form Throttle", "correct_password");

        // The web form posts to /login (intercepted by Quarkus form auth). A cookieless POST passes
        // CSRF; failures flow through PasswordIdentityProvider, which feeds the same throttle.
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            postFormLoginWrongPassword("formthrottle@example.com")
                    .then().statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)));
        }
        assertThat(loginThrottles.account().isLocked("formthrottle@example.com", FROZEN_NOW))
                .as("Repeated failed form logins must lock the account via the shared throttle")
                .isTrue();

        // The next attempt is now blocked: the provider drops the lockout cookie the login page reads.
        postFormLoginWrongPassword("formthrottle@example.com")
                .then()
                .statusCode(anyOf(equalTo(301), equalTo(302), equalTo(303)))
                .cookie("diurnal_login_lockout", not(emptyOrNullString()));
    }

    // Drives an account to the lockout threshold via the JSON API — MAX_ATTEMPTS failures, each a
    // 401. The account is locked afterwards; the NEXT attempt is the first to be blocked (429).
    private static void driveToLockout(final String email) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            postLogin(email, "wrong_password").then().statusCode(401);
        }
    }

    private static io.restassured.response.Response postLogin(final String email, final String password) {
        return given().contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .post("/api/auth/login");
    }

    private static io.restassured.response.Response postFormLoginWrongPassword(final String email) {
        return given().redirects().follow(false)
                .formParam("email", email)
                .formParam("password", "wrong_password")
                .post("/login");
    }

    // ── First-user-admin ──────────────────────────────────────────────────────

    @Test
    void register_firstUser_getsAdminRole() {
        // Table is empty at the start of every test (setUp truncates)
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"first@example.com","displayName":"First","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then().statusCode(201);

        runInTx(() -> {
            final User u = User.findByEmail("first@example.com").orElseThrow();
            assertThat(u.role)
                .as("First registered user should be admin")
                .isEqualTo(User.ROLE_ADMIN);
        });
    }

    @Test
    void register_secondUser_getsUserRole() {
        registerUser("first@example.com", "First", "password1");

        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"second@example.com","displayName":"Second","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then().statusCode(201);

        runInTx(() -> {
            final User u = User.findByEmail("second@example.com").orElseThrow();
            assertThat(u.role)
                .as("Subsequent users should be user role")
                .isEqualTo(User.ROLE_USER);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void registerUser(final String email, final String displayName, final String password) {
        given().contentType(ContentType.JSON)
                .body(String.format(
                        "{\"email\":\"%s\",\"displayName\":\"%s\",\"password\":\"%s\"}",
                        email, displayName, password))
                .post("/api/auth/register")
                .then().statusCode(201);
    }
}
