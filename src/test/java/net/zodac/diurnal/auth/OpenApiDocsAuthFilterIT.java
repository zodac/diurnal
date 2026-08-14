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
import static net.zodac.diurnal.http.HttpStatusCodes.FORBIDDEN;
import static net.zodac.diurnal.http.HttpStatusCodes.FOUND;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link OpenApiDocsAuthFilter}: the Swagger UI shell ({@code /api}) and the generated OpenAPI document ({@code /q/openapi})
 * are reachable only by an administrator, redirect an anonymous browser to {@code /login}, and are forbidden to an authenticated non-administrator.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class OpenApiDocsAuthFilterIT extends IntegrationTestBase {

    // Session creation instant on the frozen FIXED_TODAY, so tokens resolve as valid against the
    // application clock during the request.
    private static final Instant SESSION_INSTANT = Instant.parse("2026-06-15T00:00:00Z");

    @Inject
    SessionStore sessionStore;

    private User adminUser;

    private User regularUser;

    @Override
    protected void createDbState() {
        adminUser = newUser("docs-admin@lt.test", "Docs Admin", Role.ADMIN.storageValue());
        regularUser = newUser("docs-user@lt.test", "Docs User");
    }

    @Test
    void openApiDocument_anonymous_redirectsToLogin() {
        given()
            .redirects().follow(false)
            .get("/q/openapi")
            .then()
            .statusCode(FOUND)
            .header("Location", equalToIgnoringCase("/login"));
    }

    @Test
    void swaggerUi_anonymous_redirectsToLogin() {
        given()
            .redirects().follow(false)
            .get("/api")
            .then()
            .statusCode(FOUND)
            .header("Location", equalToIgnoringCase("/login"));
    }

    @Test
    void openApiDocument_nonAdministrator_isForbidden() {
        given()
            .header("Authorization", "Bearer " + tokenFor(regularUser))
            .redirects().follow(false)
            .get("/q/openapi")
            .then()
            .statusCode(FORBIDDEN);
    }

    @Test
    void swaggerUi_nonAdministrator_isForbidden() {
        given()
            .header("Authorization", "Bearer " + tokenFor(regularUser))
            .redirects().follow(false)
            .get("/api")
            .then()
            .statusCode(FORBIDDEN);
    }

    @Test
    void openApiDocument_administrator_isServed() {
        given()
            .header("Authorization", "Bearer " + tokenFor(adminUser))
            .redirects().follow(false)
            .get("/q/openapi")
            .then()
            .statusCode(OK)
            .body(containsString("openapi"));
    }

    private String tokenFor(final User user) {
        return sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, SESSION_INSTANT);
    }
}
