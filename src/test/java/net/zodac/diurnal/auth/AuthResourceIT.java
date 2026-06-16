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
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

@QuarkusTest
class AuthResourceIT extends IntegrationTestBase {

    // AuthResourceIT creates its own users — base setUp() just wipes the tables.

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
    void register_passwordTooShort_returns400() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"short@example.com","displayName":"User","password":"short"}
                        """)
                .post("/api/auth/register")
                .then().statusCode(400);
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
    void login_tokenHasValidJwtStructure() {
        registerUser("jwt@example.com", "JWT User", "password123");

        final String token = given().contentType(ContentType.JSON)
                .body("""
                        {"email":"jwt@example.com","password":"password123"}
                        """)
                .post("/api/auth/login")
                .then().statusCode(200)
                .extract().path("token");

        final String[] parts = token.split("\\.");
        assertEquals(3, parts.length, "JWT must have header.payload.signature");

        final String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertTrue(payloadJson.contains("\"iss\":\"diurnal\""), "issuer claim missing");
        assertTrue(payloadJson.contains("\"upn\":\"jwt@example.com\""), "upn claim missing");
        assertTrue(payloadJson.contains("\"exp\""), "expiry claim missing");
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
            assertEquals(User.ROLE_ADMIN, u.role, "First registered user should be admin");
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
            assertEquals(User.ROLE_USER, u.role, "Subsequent users should be user role");
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
