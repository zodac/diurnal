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
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import com.password4j.Argon2Function;
import com.password4j.types.Argon2;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuthResourceIT extends IntegrationTestBase {

    // The API register endpoint refuses to create the very first account — that must be created locally
    // via the web setup flow (covered by FirstRunIT), and the API's refusal is covered by
    // FirstUserCreationBlockedIT. Seed an initial admin here so these tests exercise the normal
    // "subsequent user" registration path (registration enabled by the test profile).
    @Override
    protected void createDbState() {
        newUser("seed-admin@example.com", "Seed Admin", Role.ADMIN.storageValue());
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
    void register_emailBelongsToOidcUser_returns409() {
        // An OIDC-provisioned account owns a `users` row keyed only on its email (no password hash);
        // a subsequent local registration must not be able to hijack or clobber that email. The
        // duplicate check (User.findByEmail) is auth-source-agnostic, and users.email is UNIQUE.
        runInTx(() -> newOidcUser("oidc-user@example.com", "OIDC User"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"oidc-user@example.com","displayName":"Impostor","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then()
                .statusCode(409)
                .body("message", containsStringIgnoringCase("already registered"));

        assertThat(User.findByEmail("oidc-user@example.com"))
                .as("The existing OIDC account must be untouched by the rejected registration")
                .hasValueSatisfying(user -> {
                    assertThat(user.displayName).as("displayName must not be overwritten").isEqualTo("OIDC User");
                    assertThat(user.passwordHash).as("no password hash may be attached to the OIDC account").isNull();
                    assertThat(user.oidcSubject).as("the OIDC subject must be preserved").isNotNull();
                });
    }

    @Test
    void register_emailBelongsToOidcUser_caseInsensitive_returns409() {
        runInTx(() -> newOidcUser("cased-oidc@example.com", "OIDC User"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"Cased-OIDC@Example.com","displayName":"Impostor","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then()
                .statusCode(409)
                .body("message", containsStringIgnoringCase("already registered"));
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

    // Global per-IP throttling (login + registration) is exercised in isolation by IpThrottleIT; it is
    // disabled in the default test profile so the tests above stay deterministic on the shared loopback.

    // ── Role assignment ────────────────────────────────────────────────────────

    @Test
    void register_viaApi_alwaysGetsUserRole() {
        // The first (admin) account is seeded via createDbState(), so any account created through the
        // API is necessarily a subsequent user and must never be granted the administrator role — the
        // API can never mint an admin (see FirstUserCreationBlockedIT for the first-run refusal).
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"second@example.com","displayName":"Second","password":"password1"}
                        """)
                .post("/api/auth/register")
                .then().statusCode(201);

        runInTx(() -> {
            final User u = User.findByEmail("second@example.com").orElseThrow();
            assertThat(u.role)
                .as("Users created via the API should always be plain users, never admins")
                .isEqualTo(Role.USER.storageValue());
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

    /**
     * Persists an OIDC-provisioned account (no password hash; issuer/subject set), mirroring what
     * {@link net.zodac.diurnal.auth.OidcUserProvisioner} writes on first login. Must be called inside a transaction.
     */
    private static void newOidcUser(final String email, final String displayName) {
        final User user = new User();
        user.email = email;
        user.displayName = displayName;
        user.oidcIssuer = "https://diurnal.example.com/idp";
        user.oidcSubject = "subject-" + email;
        user.role = Role.USER.storageValue();
        user.persist();
    }
}
