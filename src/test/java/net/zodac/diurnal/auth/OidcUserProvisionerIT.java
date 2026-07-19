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
 * Integration tests for {@link OidcUserProvisioner#linkOrCreate}: accounts are resolved by issuer + subject only (an email collision with an
 * unlinked local account refuses the login instead of silently linking), an explicitly-unverified email cannot provision an account, and IdP group
 * synchronisation never demotes the last remaining administrator.
 *
 * <p>
 * Group→role mapping is environment-dependent (a deployment may or may not configure {@code OIDC_ADMIN_GROUP}/{@code OIDC_USER_GROUP}), so — like
 * {@link RoleAssignerIT} — the tests read the same config the bean reads and branch on it rather than assuming either environment.
 */
@QuarkusTest
class OidcUserProvisionerIT extends IntegrationTestBase {

    private static final String OIDC_ISSUER = "https://diurnal.example.com/idp";

    @ConfigProperty(name = "oidc.admin.group")
    Optional<String> oidcAdminGroup = Optional.empty();

    @ConfigProperty(name = "oidc.user.group")
    Optional<String> oidcUserGroup = Optional.empty();

    @Inject
    OidcUserProvisioner oidcUserProvisioner;

    // ── Email-based linking is removed: a collision refuses, never links ───────

    @Test
    void emailCollisionWithUnlinkedLocalAccount_deniesAndDoesNotLink() {
        runInTx(() -> newUser("local@example.com", "Local User", Role.ADMIN.storageValue()));

        final JsonObject claims = oidcClaims("local@example.com", "Impersonator").put("groups", authorisingGroups());
        assertThatThrownBy(() -> oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null))
            .as("An OIDC email matching an unlinked local account must refuse the login")
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessageContaining("already exists");

        runInTx(() -> assertThat(User.findByEmail("local@example.com").orElseThrow().oidcSubject)
            .as("The refused login must not link the identity to the local account")
            .isNull());
    }

    // ── email_verified ─────────────────────────────────────────────────────────

    @Test
    void unverifiedEmail_deniesAndCreatesNoUser() {
        runInTx(() -> newUser("admin@example.com", "Admin", Role.ADMIN.storageValue()));

        final JsonObject claims = oidcClaims("unverified@example.com", "Unverified")
            .put("groups", authorisingGroups())
            .put("email_verified", false);
        assertThatThrownBy(() -> oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null))
            .as("An explicitly-unverified email must not provision an account")
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessageContaining("verified");

        runInTx(() -> assertThat(User.findByEmail("unverified@example.com"))
            .as("A refused OIDC login must not create the account")
            .isEmpty());
    }

    @Test
    void verifiedEmail_provisionsUserWithIssuerAndSubject() {
        runInTx(() -> newUser("admin@example.com", "Admin", Role.ADMIN.storageValue()));

        final JsonObject claims = oidcClaims("verified@example.com", "Verified")
            .put("groups", authorisingGroups())
            .put("email_verified", true);
        final SecurityIdentity identity = oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null);

        assertThat(identity.getPrincipal().getName())
            .as("The provisioned identity is normalised to the user's email")
            .isEqualTo("verified@example.com");

        runInTx(() -> assertThat(User.findByEmail("verified@example.com"))
            .as("A verified email provisions the account with the issuer + subject pair stored")
            .hasValueSatisfying(user -> {
                assertThat(user.oidcIssuer)
                    .as("unexpected issuer")
                    .isEqualTo(OIDC_ISSUER);
                assertThat(user.oidcSubject)
                    .as("unexpected subject")
                    .isEqualTo("subject-verified@example.com");
                assertThat(user.passwordHash)
                    .as("An OIDC-provisioned account has no password")
                    .isNull();
            }));
    }

    @Test
    void provision_nameClaimEqualToEmail_usesLocalPartInstead() {
        // Some IdPs fill the name claim with the username/email when no display name is configured; a full email address is never a useful
        // display name, so provisioning falls back to the email's local part.
        runInTx(() -> newUser("admin@example.com", "Admin", Role.ADMIN.storageValue()));

        final JsonObject claims = oidcClaims("named@example.com", "NAMED@example.com").put("groups", authorisingGroups());
        oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null);

        runInTx(() -> assertThat(User.findByEmail("named@example.com").orElseThrow().displayName)
            .as("a name claim that is just the email must fall back to its local part")
            .isEqualTo("named"));
    }

    // ── Linked accounts are resolved by issuer + subject only ─────────────────

    @Test
    void linkedAccount_authenticatesByIssuerAndSubject_notEmail() {
        runInTx(() -> {
            newUser("admin@example.com", "Admin", Role.ADMIN.storageValue());
            final User linked = newUser("linked@example.com", "Linked User");
            linked.oidcIssuer = OIDC_ISSUER;
            linked.oidcSubject = "subject-linked";
            linked.persist();
        });

        // The token presents a different email — the account must still resolve via issuer + subject.
        final JsonObject claims = oidcClaims("renamed@example.com", "Linked User")
            .put("groups", authorisingGroups())
            .put("sub", "subject-linked");
        final SecurityIdentity identity = oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null);

        assertThat(identity.getPrincipal().getName())
            .as("The linked account is resolved by issuer + subject, not by the token's email")
            .isEqualTo("linked@example.com");
    }

    // ── Group synchronisation vs the last administrator ───────────────────────

    @Test
    void groupSyncDemotingLastAdministrator_deniesAndKeepsRole() {
        // Only expressible when a user group is configured (the IdP-derived role must be "user");
        // without group config the same login simply succeeds with no role change (asserted instead).
        runInTx(() -> {
            final User admin = newUser("solo-admin@example.com", "Solo Admin", Role.ADMIN.storageValue());
            admin.oidcIssuer = OIDC_ISSUER;
            admin.oidcSubject = "subject-solo-admin";
            admin.persist();
        });

        final Optional<String> userGroup = oidcUserGroup.filter(group -> !group.isBlank());
        final JsonObject claims = oidcClaims("solo-admin@example.com", "Solo Admin")
            .put("sub", "subject-solo-admin")
            .put("groups", userGroup.map(List::of).orElseGet(List::of));

        if (userGroup.isPresent()) {
            assertThatThrownBy(() -> oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null))
                .as("Demoting the last administrator via IdP groups must refuse the login")
                .isInstanceOf(AuthenticationFailedException.class)
                .hasMessageContaining("could not be updated");
        } else {
            final SecurityIdentity identity = oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null);
            assertThat(identity.getRoles())
                .as("Without group config the administrator logs in unchanged")
                .contains(Role.ADMIN.storageValue());
        }

        runInTx(() -> assertThat(User.findByEmail("solo-admin@example.com").orElseThrow().role)
            .as("The last administrator's role must be untouched either way")
            .isEqualTo(Role.ADMIN.storageValue()));
    }

    @Test
    void groupSyncDemotion_withAnotherAdministrator_applies() {
        // The demotion refusal is only about the LAST administrator: with a second admin present the
        // group-derived demotion applies normally (when a user group is configured in this environment).
        runInTx(() -> {
            newUser("other-admin@example.com", "Other Admin", Role.ADMIN.storageValue());
            final User admin = newUser("demoted@example.com", "Demoted Admin", Role.ADMIN.storageValue());
            admin.oidcIssuer = OIDC_ISSUER;
            admin.oidcSubject = "subject-demoted";
            admin.persist();
        });

        final Optional<String> userGroup = oidcUserGroup.filter(group -> !group.isBlank());
        final JsonObject claims = oidcClaims("demoted@example.com", "Demoted Admin")
            .put("sub", "subject-demoted")
            .put("groups", userGroup.map(List::of).orElseGet(List::of));

        final SecurityIdentity identity = oidcUserProvisioner.linkOrCreate(claims, new IdTokenCredential("dummy.token"), null);
        assertThat(identity.getPrincipal().getName())
            .as("The login itself succeeds")
            .isEqualTo("demoted@example.com");

        final String expectedRole = userGroup.isPresent() ? Role.USER.storageValue() : Role.ADMIN.storageValue();
        runInTx(() -> assertThat(User.findByEmail("demoted@example.com").orElseThrow().role)
            .as("With another administrator present the IdP-derived role applies (or is untouched without group config)")
            .isEqualTo(expectedRole));
    }

    // A group that authorises the login whatever this environment configures: prefer the user group, fall back to the admin group, or none when
    // group mapping is not configured at all (then no group is required). Mirrors FirstUserCreationBlockedIT.
    private List<String> authorisingGroups() {
        return oidcUserGroup.filter(group -> !group.isBlank())
            .or(() -> oidcAdminGroup.filter(group -> !group.isBlank()))
            .map(List::of)
            .orElseGet(List::of);
    }

    private static JsonObject oidcClaims(final String email, final String name) {
        return new JsonObject()
            .put("sub", "subject-" + email)
            .put("iss", OIDC_ISSUER)
            .put("email", email)
            .put("name", name);
    }
}
