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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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
 * Integration tests for the password-auth-disabled migration path: with password authentication OFF ({@link OidcOnlyAuthProfile}), an OIDC login
 * whose email matches an unlinked local account ADOPTS it — attaching the issuer + subject pair and removing the password — instead of refusing
 * (Settings → Connect does not exist without a password to sign in with). An explicitly-unverified email still never adopts.
 *
 * <p>
 * Like {@link OidcUserProvisionerIT}, group→role mapping is environment-dependent, so the tests read the same config the bean reads and present an
 * authorising group when one is configured.
 */
@QuarkusTest
@TestProfile(OidcOnlyAuthProfile.class)
class OidcEmailAdoptionIT extends IntegrationTestBase {

    private static final String OIDC_ISSUER = "https://diurnal.example.com/idp";

    @ConfigProperty(name = "oidc.admin.group")
    Optional<String> oidcAdminGroup = Optional.empty();

    @ConfigProperty(name = "oidc.user.group")
    Optional<String> oidcUserGroup = Optional.empty();

    @Inject
    OidcUserProvisioner oidcUserProvisioner;

    @Test
    void emailCollision_withPasswordAuthDisabled_adoptsAccountAndRemovesPassword() {
        // A plain user, so a user-group-configured environment's role sync is a no-op (the last-admin case has its own test below).
        runInTx(() -> newUser("migrated@example.com", "Migrated User"));

        // The token deliberately presents a DIFFERENT name, proving adoption keeps the local one.
        final JsonObject claims = oidcClaims("migrated@example.com", "IdP-Side Name").put("email_verified", true);
        final SecurityIdentity identity = oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null);

        assertThat(identity.getPrincipal().getName())
            .as("The adopted identity is normalised to the account's email")
            .isEqualTo("migrated@example.com");

        runInTx(() -> assertThat(User.findByEmail("migrated@example.com"))
            .as("Adoption must attach the identity and remove the password (OIDC-only sign-in)")
            .hasValueSatisfying(user -> {
                assertThat(user.oidcSubject)
                    .as("unexpected subject")
                    .isEqualTo("subject-migrated@example.com");
                assertThat(user.authSource())
                    .as("unexpected auth source")
                    .isEqualTo("oidc");
                assertThat(user.displayName)
                    .as("adoption keeps the locally-chosen display name, ignoring the token's name claim")
                    .isEqualTo("Migrated User");
            }));
    }

    @Test
    void emailCollision_withUnverifiedEmail_isStillRefused() {
        runInTx(() -> newUser("migrated@example.com", "Migrated User"));

        final JsonObject claims = oidcClaims("migrated@example.com", "Migrated User").put("email_verified", false);
        assertThatThrownBy(() -> oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null))
            .as("An explicitly-unverified email must never adopt an existing account")
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessageContaining("verified");

        runInTx(() -> assertThat(User.findByEmail("migrated@example.com").orElseThrow().oidcSubject)
            .as("A refused adoption must not link the account")
            .isNull());
    }

    @Test
    void adoption_ofSoloAdministrator_succeedsWhenAdminRoleIsPreserved() {
        // Adopting the deployment's only administrator is fine as long as the account REMAINS an administrator afterwards (the IdP asserts admin
        // via the configured group, or no group mapping leaves the role untouched); only a demotion of the last administrator is refused. The
        // group config is environment-dependent, so each configuration exercises the branch it can express.
        runInTx(() -> newUser("solo-admin@example.com", "Solo Admin", Role.ADMIN.storageValue()));

        final Optional<String> adminGroup = oidcAdminGroup.filter(group -> !group.isBlank());
        final Optional<String> userGroup = oidcUserGroup.filter(group -> !group.isBlank());
        final JsonObject claims = oidcClaims("solo-admin@example.com", "Solo Admin")
            .put("groups", adminGroup.or(() -> userGroup).map(List::of).orElseGet(List::of));

        if (adminGroup.isEmpty() && userGroup.isPresent()) {
            // The only expressible IdP role is "user" — adopting would demote the last administrator, so the login is refused untouched.
            assertThatThrownBy(() -> oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null))
                .as("An adoption that would demote the last administrator must be refused")
                .isInstanceOf(AuthenticationFailedException.class);

            runInTx(() -> assertThat(User.findByEmail("solo-admin@example.com").orElseThrow().oidcSubject)
                .as("A refused adoption must not link the account")
                .isNull());
        } else {
            final SecurityIdentity identity = oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null);
            assertThat(identity.getRoles())
                .as("The adopted administrator keeps the admin role")
                .contains(Role.ADMIN.storageValue());

            runInTx(() -> assertThat(User.findByEmail("solo-admin@example.com").orElseThrow().authSource())
                .as("The adopted administrator is converted to OIDC-only sign-in")
                .isEqualTo("oidc"));
        }

        runInTx(() -> assertThat(User.findByEmail("solo-admin@example.com").orElseThrow().role)
            .as("The administrator role survives either way")
            .isEqualTo(Role.ADMIN.storageValue()));
    }

    @Test
    void firstUser_viaOidc_isRefusedEvenWithPasswordAuthDisabled() {
        // The very first account must ALWAYS be created locally through the setup flow — in a pure-OIDC deployment it is the sysops break-glass
        // administrator — so OIDC never provisions user number one.
        final JsonObject claims = oidcClaims("first@example.com", "First User");
        assertThatThrownBy(() -> oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null))
            .as("OIDC must not provision the initial account, even in a pure-OIDC deployment")
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessageContaining("created locally");

        runInTx(() -> assertThat(User.count())
            .as("A refused first login must not create the account")
            .isZero());
    }

    private JsonObject oidcClaims(final String email, final String name) {
        // An authorising group (when configured) keeps the login authorised in every environment.
        final List<String> groups = oidcUserGroup.filter(group -> !group.isBlank())
            .or(() -> oidcAdminGroup.filter(group -> !group.isBlank()))
            .map(List::of)
            .orElseGet(List::of);
        return new JsonObject()
            .put("sub", "subject-" + email)
            .put("iss", OIDC_ISSUER)
            .put("email", email)
            .put("name", name)
            .put("groups", groups);
    }
}
