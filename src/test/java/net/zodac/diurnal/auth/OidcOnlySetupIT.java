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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Verifies the first-run setup flow in a pure-OIDC deployment ({@link OidcOnlyAuthProfile}: password auth disabled): the initial account must STILL
 * be created locally — it is the sysops break-glass administrator — so the setup pages ignore {@code PASSWORD_AUTH_ENABLED} until a user exists,
 * and lock back down the moment one does.
 */
@QuarkusTest
@TestProfile(OidcOnlyAuthProfile.class)
class OidcOnlySetupIT extends IntegrationTestBase {

    // createDbState() not overridden — each test starts in the "no users yet" state

    @Test
    void loginPage_firstRun_redirectsToSetup() {
        given().redirects().follow(false)
                .get("/login")
                .then().statusCode(303)
                .header("Location", endsWith("/welcome"));
    }

    @Test
    void welcomePage_firstRun_renders() {
        // The page content is deliberately identical to a password-auth deployment's — the deployer configured the auth mode and owns that context.
        given().get("/welcome")
                .then().statusCode(200)
                .body(containsString("Create administrator account"));
    }

    @Test
    void register_firstRun_createsTheLocalAdministrator() {
        given().redirects().follow(false)
                .formParam("email", "sysops@example.com")
                .formParam("displayName", "Sysops Admin")
                .formParam("password", "backup_password_1")
                .formParam("confirmPassword", "backup_password_1")
                .post("/register")
                .then().statusCode(303);

        runInTx(() -> assertThat(User.findByEmail("sysops@example.com"))
            .as("The setup registration must create a local administrator even with password auth disabled")
            .hasValueSatisfying(user -> {
                assertThat(user.role)
                    .as("the first account is the administrator")
                    .isEqualTo(Role.ADMIN.storageValue());
                assertThat(user.passwordHash)
                    .as("the break-glass account holds a password")
                    .isNotNull();
                assertThat(user.displayName)
                    .as("the submitted display name is stored exactly, never the email")
                    .isEqualTo("Sysops Admin");
            }));
    }

    @Test
    @TestSecurity(user = "sysops@example.com", roles = Role.Values.USER)
    void settingsPage_unlinkedAccount_offersConnectEvenWithPasswordAuthDisabled() {
        // The Connect button is deliberately NOT gated on canChangePassword (false whenever password auth is off) — the unlinked local admin
        // migrating to OIDC must still see it.
        runInTx(() -> newUser("sysops@example.com", "Sysops Admin", Role.ADMIN.storageValue()));

        given().get("/settings")
                .then().statusCode(200)
                .body(containsString("/internal/settings/oidc/connect"));
    }

    @Test
    @TestSecurity(user = "sysops@example.com", roles = Role.Values.USER)
    void breakGlassAdministrator_canChangeItsPasswordDespitePasswordAuthDisabled() {
        // Password MANAGEMENT keys on holding a password, not on password LOGIN being enabled: the break-glass administrator must be able to
        // maintain its credential while password sign-in is off.
        runInTx(() -> newUser("sysops@example.com", "Sysops Admin", Role.ADMIN.storageValue()));
        final String[] originalHash = new String[1];
        runInTx(() -> originalHash[0] = User.findByEmail("sysops@example.com").orElseThrow().passwordHash);

        given().get("/settings")
                .then().statusCode(200)
                .body(containsString("id=\"password-view\""));

        given().formParam("currentPassword", TEST_PASSWORD)
                .formParam("newPassword", "new_backup_password_1")
                .formParam("confirmPassword", "new_backup_password_1")
                .post("/internal/settings/password")
                .then().statusCode(200);

        runInTx(() -> assertThat(User.findByEmail("sysops@example.com").orElseThrow().passwordHash)
            .as("the break-glass password must have been re-hashed to the new value")
            .isNotNull()
            .isNotEqualTo(originalHash[0]));
    }

    @Test
    void afterSetup_localRegistrationLocksBackDown() {
        runInTx(() -> newUser("sysops@example.com", "Sysops Admin", Role.ADMIN.storageValue()));

        given().get("/register")
                .then().statusCode(404);
        given().redirects().follow(false)
                .formParam("email", "second@example.com")
                .formParam("displayName", "Second User")
                .formParam("password", "irrelevant_1")
                .formParam("confirmPassword", "irrelevant_1")
                .post("/register")
                .then().statusCode(404);
        given().redirects().follow(false)
                .get("/welcome")
                .then().statusCode(303)
                .header("Location", endsWith("/login"));

        runInTx(() -> assertThat(User.count())
            .as("No account may be created after setup while password auth is disabled")
            .isEqualTo(1L));
    }
}
