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

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OidcLoginPolicy#decide(OidcLoginFacts)}: every branch of the OIDC sign-in decision — the first-run bootstrap guard, the
 * missing-email and unverified-email refusals, the configured-group authorisation, the linked-account path (including the last-administrator
 * demotion refusal), the unlinked email-collision refusal, and provisioning.
 */
class OidcLoginPolicyTest {

    @Test
    void decide_firstUserBootstrapBlocked_denies() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(true, true, false, false, false, false, false, false, null));
        assertThat(decision)
            .as("The initial account must not be provisioned via OIDC")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.SETUP_REQUIRED));
    }

    @Test
    void decide_emailMissing_denies() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, true, false, false, false, false, false, null));
        assertThat(decision)
            .as("A token without an email claim must be refused")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.EMAIL_MISSING));
    }

    @Test
    void decide_groupCheckEnabledAndNotInGroup_denies() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, true, false, true, false, false, null));
        assertThat(decision)
            .as("With group mapping configured, a user in no configured group must be refused — even one already linked")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.NOT_IN_GROUP));
    }

    @Test
    void decide_groupCheckDisabled_doesNotRequireGroup() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, false, false, false, false, false, null));
        assertThat(decision)
            .as("Without group mapping configured, no group membership is required")
            .isEqualTo(new OidcLoginDecision.ProvisionNew());
    }

    @Test
    void decide_linkedAccount_usesExisting() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, true, true, true, false, false, null));
        assertThat(decision)
            .as("A linked account (issuer + subject match) authenticates as that account")
            .isEqualTo(new OidcLoginDecision.UseExisting());
    }

    @Test
    void decide_linkedAccountDemotingLastAdministrator_denies() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, true, true, true, true, false, null));
        assertThat(decision)
            .as("A group-synchronised demotion of the last administrator must refuse the login")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.ROLE_SYNC_REFUSED));
    }

    @Test
    void decide_unlinkedEmailCollision_denies() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, false, false, false, false, true, true));
        assertThat(decision)
            .as("An email matching an unlinked local account must refuse (never auto-link by email)")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.ACCOUNT_EXISTS));
    }

    @Test
    void decide_linkedAccountWins_overEmailCollision() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, false, false, true, false, true, null));
        assertThat(decision)
            .as("A linked account authenticates regardless of any email-collision flag")
            .isEqualTo(new OidcLoginDecision.UseExisting());
    }

    @Test
    void decide_emailVerifiedFalse_denies() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, false, false, false, false, false, false));
        assertThat(decision)
            .as("An explicitly-unverified email must not provision an account")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.EMAIL_UNVERIFIED));
    }

    @Test
    void decide_emailVerifiedTrue_provisions() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, false, false, false, false, false, true));
        assertThat(decision)
            .as("A verified email provisions a new account")
            .isEqualTo(new OidcLoginDecision.ProvisionNew());
    }

    @Test
    void decide_emailVerifiedAbsent_provisions() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, false, false, false, false, false, null));
        assertThat(decision)
            .as("A provider that does not emit email_verified may still provision")
            .isEqualTo(new OidcLoginDecision.ProvisionNew());
    }

    @Test
    void decide_emailCollisionWithPasswordAuthDisabled_adoptsTheAccount() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, false, false, false, false, false, false, true, true));
        assertThat(decision)
            .as("With password auth disabled the IdP is the sole authority, so a verified email adopts the unlinked local account")
            .isEqualTo(new OidcLoginDecision.AdoptByEmail());
    }

    @Test
    void decide_emailCollisionWithPasswordAuthDisabled_absentVerifiedClaim_adopts() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, false, false, false, false, false, false, true, null));
        assertThat(decision)
            .as("A provider that does not emit email_verified may still adopt")
            .isEqualTo(new OidcLoginDecision.AdoptByEmail());
    }

    @Test
    void decide_adoptionWithUnverifiedEmail_denies() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, false, false, false, false, false, false, true, false));
        assertThat(decision)
            .as("An explicitly-unverified email must never adopt an existing account")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.EMAIL_UNVERIFIED));
    }

    @Test
    void decide_adoptionDemotingLastAdministrator_denies() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, false, false, true, true, false, true, true, true));
        assertThat(decision)
            .as("Adoption applies the IdP-derived role, so it must refuse a last-administrator demotion like any login")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.ROLE_SYNC_REFUSED));
    }

    @Test
    void decide_emailCollisionWithPasswordAuthEnabledAndUnverifiedEmail_reportsAccountExists() {
        final OidcLoginDecision decision = OidcLoginPolicy.decide(facts(false, true, false, false, false, false, false, true, false));
        assertThat(decision)
            .as("With password auth enabled the collision refusal (pointing at Settings → Connect) takes precedence over the unverified email")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.ACCOUNT_EXISTS));
    }

    @Test
    void revocationGuard_callbackPath_isExempt() {
        assertThat(OidcLoginPolicy.revocationGuardSatisfied(true, false))
            .as("The code-flow callback runs before a Diurnal session exists, so it must be exempt")
            .isTrue();
    }

    @Test
    void revocationGuard_liveSessionForSameUser_passes() {
        assertThat(OidcLoginPolicy.revocationGuardSatisfied(false, true))
            .as("A live server-side session for the same user satisfies the guard")
            .isTrue();
    }

    @Test
    void revocationGuard_qSessionAlone_fails() {
        assertThat(OidcLoginPolicy.revocationGuardSatisfied(false, false))
            .as("The OIDC session cookie alone must never grant page access — revocation would be unenforceable")
            .isFalse();
    }

    private static OidcLoginFacts facts(final boolean firstUserBootstrapBlocked, final boolean passwordAuthEnabled, final boolean emailMissing,
        final boolean groupCheckEnabled, final boolean inConfiguredGroup, final boolean linkedAccountFound, final boolean demotesLastAdministrator,
        final boolean emailCollision, final @Nullable Boolean emailVerified) {
        return new OidcLoginFacts(firstUserBootstrapBlocked, passwordAuthEnabled, emailMissing, groupCheckEnabled, inConfiguredGroup,
            linkedAccountFound, demotesLastAdministrator, emailCollision, emailVerified);
    }
}
