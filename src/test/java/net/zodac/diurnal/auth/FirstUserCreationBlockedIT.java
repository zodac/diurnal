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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsStringIgnoringCase;

import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

/**
 * Locks down the first-run bootstrap: until the initial (administrator) account has been created locally through the web setup flow, neither the JSON
 * API ({@code POST /api/v1/auth/register}) nor OIDC provisioning ({@link OidcUserProvisioner}) may create a user. This prevents an unauthenticated
 * caller from seizing the very first account — which is always granted administrator rights. Once that account exists, both paths resume normally.
 *
 * <p>
 * The base class truncates every table before each test and this class seeds nothing, so each test starts in the "no users yet" state and seeds the
 * initial account itself when it needs to assert the block has lifted. The default profile leaves registration enabled, so any refusal here is the
 * first-run guard, not the registration switch (that interaction is covered by {@link AuthRegistrationDisabledIT}).
 */
@QuarkusTest
class FirstUserCreationBlockedIT extends IntegrationTestBase {

    private static final String OIDC_ISSUER = "https://diurnal.example.com/idp";

    // OIDC group→role mapping is only active when a group is configured, and the group names are
    // environment-dependent (a deployment may or may not set them). Read the same config the bean
    // reads so the positive test presents a group that authorises the login in any environment.
    @ConfigProperty(name = "oidc.admin.group")
    Optional<String> oidcAdminGroup = Optional.empty();

    @ConfigProperty(name = "oidc.user.group")
    Optional<String> oidcUserGroup = Optional.empty();

    @Inject
    OidcUserProvisioner oidcUserProvisioner;

    // ── JSON API (POST /api/v1/auth/register) ─────────────────────────────────────

    @Test
    void apiRegister_firstRun_isForbiddenAndCreatesNoUser() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"first@example.com","displayName":"First","password":"password1"}
                        """)
                .post("/api/v1/auth/register")
                .then()
                .statusCode(403)
                .body("message", containsStringIgnoringCase("must be created via the setup page"));

        runInTx(() -> assertThat(User.count())
            .as("The API must not create the initial account")
            .isZero());
    }

    @Test
    void apiRegister_afterInitialAccountExists_succeeds() {
        // The web setup flow having created the initial admin lifts the block for the API.
        runInTx(() -> newUser("admin@example.com", "Admin", Role.ADMIN.storageValue()));

        given().contentType(ContentType.JSON)
                .body("""
                        {"email":"api-user@example.com","displayName":"API User","password":"password1"}
                        """)
                .post("/api/v1/auth/register")
                .then()
                .statusCode(201);

        runInTx(() -> assertThat(User.findByEmail("api-user@example.com"))
            .as("The API may create subsequent users once the initial account exists")
            .isPresent());
    }

    // ── OIDC provisioning (OidcUserProvisioner.linkOrCreate) ───────────────────

    @Test
    void oidcProvisioning_firstRun_isRefusedAndCreatesNoUser() {
        final JsonObject claims = oidcClaims("oidc-first@example.com", "OIDC First");

        assertThatThrownBy(() -> oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token")))
            .as("OIDC must not provision the initial account while local auth is available")
            .isInstanceOf(AuthenticationFailedException.class);

        runInTx(() -> assertThat(User.count())
            .as("A refused OIDC login must not create the initial account")
            .isZero());
    }

    @Test
    void oidcProvisioning_afterInitialAccountExists_provisionsUser() {
        // Once the initial admin has been created locally, OIDC provisioning resumes as normal.
        runInTx(() -> newUser("admin@example.com", "Admin", Role.ADMIN.storageValue()));

        // Present a configured group (if any) so the login is authorised whatever the environment sets,
        // and derive the role it must map to from the same config the provisioner uses.
        final List<String> groups = authorisingGroup().map(List::of).orElseGet(List::of);
        final JsonObject claims = oidcClaims("oidc-user@example.com", "OIDC User").put("groups", groups);
        final SecurityIdentity identity = oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"));

        assertThat(identity.getPrincipal().getName())
            .as("The provisioned identity is normalised to the user's email")
            .isEqualTo("oidc-user@example.com");

        runInTx(() -> assertThat(User.findByEmail("oidc-user@example.com"))
            .as("A subsequent OIDC login provisions the user (the first-run block has lifted)")
            .hasValueSatisfying(user -> assertThat(user.role).isEqualTo(expectedRole())));
    }

    // The group presented in the positive test's claims: prefer the user group, fall back to the admin
    // group, or none when neither is configured. This keeps the login authorised in every environment.
    private Optional<String> authorisingGroup() {
        if (oidcUserGroup.filter(g -> !g.isBlank()).isPresent()) {
            return oidcUserGroup;
        }
        return oidcAdminGroup.filter(g -> !g.isBlank());
    }

    // The role the presented group maps to: the user group → user, the admin group → admin, and with no
    // group configured a subsequent account defaults to the plain user role.
    private String expectedRole() {
        if (oidcUserGroup.filter(g -> !g.isBlank()).isPresent()) {
            return Role.USER.storageValue();
        }
        if (oidcAdminGroup.filter(g -> !g.isBlank()).isPresent()) {
            return Role.ADMIN.storageValue();
        }
        return Role.USER.storageValue();
    }

    private static JsonObject oidcClaims(final String email, final String name) {
        return new JsonObject()
                .put("sub", "subject-" + email)
                .put("iss", OIDC_ISSUER)
                .put("email", email)
                .put("name", name);
    }
}
