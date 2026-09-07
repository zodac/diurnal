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

package net.zodac.diurnal.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * The parts of {@link OidcUserProvisioner} that a deployment's own configuration otherwise decides: which local role an IdP group maps to, what a
 * token that names no known group earns, and which claims count as an email address at all.
 *
 * <p>
 * {@code OidcUserProvisionerIT} covers the same bean against the INJECTED configuration and so branches on whether groups are named; under
 * {@link OidcGroupsProfile} both are, and every expectation below is unconditional. It also enters through
 * {@link OidcUserProvisioner#augment(SecurityIdentity, AuthenticationRequestContext)} rather than calling {@code linkOrCreate} directly, which is
 * what puts the ID token's own decoding - the step the live flow always performs and a direct call never does - under test.
 */
@QuarkusTest
@TestProfile(OidcGroupsProfile.class)
class OidcGroupProvisioningIT extends IntegrationTestBase {

    private static final String OIDC_ISSUER = "https://diurnal.example.com/idp";
    private static final String EXISTING_ADMIN = "existing-admin@example.com";

    @Inject
    OidcUserProvisioner oidcUserProvisioner;

    @Override
    protected void createDbState() {
        // OIDC never provisions the FIRST account (the local break-glass administrator), so every case below needs one to already exist.
        newUser(EXISTING_ADMIN, "Existing Admin", Role.ADMIN.storageValue());
    }

    // ── Group membership decides the role ─────────────────────────────────────

    @Test
    void adminGroupMember_isProvisionedAsAnAdministrator() {
        final SecurityIdentity identity = signIn(claims("admin-member@example.com", "Admin Member")
            .put("groups", List.of(OidcGroupsProfile.ADMIN_GROUP)));

        assertThat(identity.getRoles())
            .as("a member of the configured admin group signs in as an administrator")
            .contains(Role.ADMIN.storageValue());
        runInTx(() -> assertThat(User.findByEmail("admin-member@example.com").orElseThrow().role)
            .as("and the provisioned account is stored with that role")
            .isEqualTo(Role.ADMIN.storageValue()));
    }

    @Test
    void userGroupMember_isProvisionedAsAnOrdinaryUser() {
        final SecurityIdentity identity = signIn(claims("user-member@example.com", "User Member")
            .put("groups", List.of(OidcGroupsProfile.USER_GROUP)));

        assertThat(identity.getRoles())
            .as("a member of the configured user group signs in without the administrator role")
            .isNotEmpty()
            .doesNotContain(Role.ADMIN.storageValue());
        runInTx(() -> assertThat(User.findByEmail("user-member@example.com").orElseThrow().role)
            .as("and the provisioned account is stored as an ordinary user")
            .isEqualTo(Role.USER.storageValue()));
    }

    @Test
    void memberOfNoConfiguredGroup_isRefusedAndProvisionsNothing() {
        final JsonObject claims = claims("outsider@example.com", "Outsider").put("groups", List.of("some-other-group"));

        assertThatThrownBy(() -> signIn(claims))
            .as("once groups are configured, membership of one of them is what authorises the login at all")
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessageContaining("not authorised");

        runInTx(() -> assertThat(User.findByEmail("outsider@example.com"))
            .as("a refused login must leave no account behind")
            .isEmpty());
    }

    // ── Group membership is re-applied on every sign-in ───────────────────────

    @Test
    void groupChange_promotesAnExistingAccountOnItsNextSignIn() {
        signIn(claims("promoted@example.com", "Promoted").put("groups", List.of(OidcGroupsProfile.USER_GROUP)));

        signIn(claims("promoted@example.com", "Promoted").put("groups", List.of(OidcGroupsProfile.ADMIN_GROUP)));

        runInTx(() -> assertThat(User.findByEmail("promoted@example.com").orElseThrow().role)
            .as("IdP groups win on every login, so a group added at the provider takes effect at the next sign-in")
            .isEqualTo(Role.ADMIN.storageValue()));
    }

    @Test
    void groupChangeDemotingTheLastAdministrator_isRefusedAndKeepsTheRole() {
        // The seeded local administrator is deliberately not an OIDC account, so the account signing in below is the only one the demotion could
        // apply to - and it is the last administrator only once the local one is gone.
        signIn(claims("sole-admin@example.com", "Sole Admin").put("groups", List.of(OidcGroupsProfile.ADMIN_GROUP)));
        runInTx(() -> User.findByEmail(EXISTING_ADMIN).orElseThrow().delete());

        final JsonObject demoting = claims("sole-admin@example.com", "Sole Admin").put("groups", List.of(OidcGroupsProfile.USER_GROUP));
        assertThatThrownBy(() -> signIn(demoting))
            .as("a group change that would leave the deployment with no administrator is refused rather than applied")
            .isInstanceOf(AuthenticationFailedException.class);

        runInTx(() -> assertThat(User.findByEmail("sole-admin@example.com").orElseThrow().role)
            .as("and the refused demotion leaves the role untouched")
            .isEqualTo(Role.ADMIN.storageValue()));
    }

    // ── What counts as an email address ───────────────────────────────────────

    @Test
    void emailInPreferredUsername_isUsedWhenThereIsNoEmailClaim() {
        // Keycloak and friends put the address in preferred_username; it is accepted only when it actually looks like one.
        final JsonObject claims = claims("ignored@example.com", "Preferred")
            .put("groups", List.of(OidcGroupsProfile.USER_GROUP))
            .put("preferred_username", "preferred@example.com");
        claims.remove("email");

        assertThat(signIn(claims).getPrincipal().getName())
            .as("an address carried in preferred_username is the account's email")
            .isEqualTo("preferred@example.com");
    }

    @Test
    void preferredUsernameThatIsNotAnAddress_isRefusedAsNoEmailAtAll() {
        final JsonObject claims = claims("ignored@example.com", "Login Name")
            .put("groups", List.of(OidcGroupsProfile.USER_GROUP))
            .put("preferred_username", "loginname");
        claims.remove("email");

        assertThatThrownBy(() -> signIn(claims))
            .as("a username is not an email address, and provisioning an account keyed on one would key it on nothing")
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessageContaining("email address");
    }

    @Test
    void emailIsCaseFoldedBeforeItIsStored() {
        assertThat(signIn(claims("MiXeD@Example.COM", "Mixed Case").put("groups", List.of(OidcGroupsProfile.USER_GROUP)))
            .getPrincipal().getName())
            .as("the address is folded before it is checked, so the value that reaches the column is the folded one")
            .isEqualTo("mixed@example.com");
    }

    // ── email_verified, in each of the shapes providers emit it ───────────────

    @Test
    void emailVerifiedAsTheStringFalse_isRefused() {
        // Some providers emit the claim as a string rather than a boolean; "false" must deny exactly as false does.
        final JsonObject claims = claims("unverified@example.com", "Unverified")
            .put("groups", List.of(OidcGroupsProfile.USER_GROUP))
            .put("email_verified", "false");

        assertThatThrownBy(() -> signIn(claims))
            .as("an explicitly unverified address must not claim or create an account, whichever type the claim carries")
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessageContaining("not been verified");
    }

    @Test
    void emailVerifiedAsTheStringTrue_isAccepted() {
        final JsonObject claims = claims("verified@example.com", "Verified")
            .put("groups", List.of(OidcGroupsProfile.USER_GROUP))
            .put("email_verified", "true");

        assertThat(signIn(claims).getPrincipal().getName())
            .as("a string \"true\" is as good as the boolean")
            .isEqualTo("verified@example.com");
    }

    @Test
    void absentEmailVerified_isTreatedAsVerified() {
        // Plenty of providers never emit the claim; reading silence as "unverified" would refuse every login from them.
        assertThat(signIn(claims("silent@example.com", "Silent").put("groups", List.of(OidcGroupsProfile.USER_GROUP)))
            .getPrincipal().getName())
            .as("only an explicit denial counts, so an absent claim provisions normally")
            .isEqualTo("silent@example.com");
    }

    // ── The ID token itself ───────────────────────────────────────────────────

    @Test
    void identityWithNoIdToken_isPassedThroughUntouched() {
        final SecurityIdentity anonymous = QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal("nobody")).build();

        assertThat(augment(anonymous))
            .as("an identity carrying no ID token is not an OIDC sign-in, and must be returned exactly as it arrived")
            .isSameAs(anonymous);
    }

    @Test
    void malformedIdToken_isRefused() {
        final SecurityIdentity identity = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("nobody"))
            .addCredential(new IdTokenCredential("not-a-jwt"))
            .build();

        assertThatThrownBy(() -> augment(identity))
            .as("a token that is not a JWT at all cannot be read, and must fail authentication rather than sign anybody in")
            .isInstanceOf(AuthenticationFailedException.class)
            .hasMessageContaining("Malformed OIDC ID token");
    }

    private SecurityIdentity signIn(final JsonObject claims) {
        return augment(QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(claims.getString("sub")))
            .addCredential(new IdTokenCredential(idToken(claims)))
            .build());
    }

    // The augmenter is the live entry point: Quarkus hands it the verified identity, and everything the provisioner decides follows from the claims
    // it decodes out of the token itself. There is no RoutingContext on a direct call, which is the "no request context" path the bean documents.
    private SecurityIdentity augment(final SecurityIdentity identity) {
        final AuthenticationRequestContext context = request -> Uni.createFrom().item(request.get());
        return oidcUserProvisioner.augment(identity, context).await().indefinitely();
    }

    // A signed token is never needed: Quarkus has already verified the signature by the time the augmenter runs, so the provisioner reads the
    // payload segment alone. The header and signature are placeholders.
    private static String idToken(final JsonObject claims) {
        final String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims.encode().getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    private static JsonObject claims(final String email, final String name) {
        return new JsonObject()
            .put("sub", "subject-" + email)
            .put("iss", OIDC_ISSUER)
            .put("email", email)
            .put("name", name);
    }
}
