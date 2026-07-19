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

import net.zodac.diurnal.auth.OidcLinkPolicy.IdentityOwner;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OidcLinkPolicy#decide}: every branch of the Settings "Connect {provider}" decision — the configured-group authorisation, the
 * identity-ownership conflicts, the already-linked refusal, the last-administrator demotion refusal, the re-login pass-through and the link itself.
 */
class OidcLinkPolicyTest {

    @Test
    void decide_groupCheckEnabledAndNotInGroup_denies() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(true, false, IdentityOwner.NONE, false, false, false, true);
        assertThat(decision)
            .as("With group mapping configured, a user in no configured group must be refused")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.NOT_IN_GROUP));
    }

    @Test
    void decide_identityOwnedByAnotherAccount_denies() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(false, false, IdentityOwner.OTHER_USER, false, false, false, true);
        assertThat(decision)
            .as("An identity already linked to a different account must refuse the link")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.LINK_CONFLICT));
    }

    @Test
    void decide_sessionUserAlreadyLinkedElsewhere_denies() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(false, false, IdentityOwner.NONE, true, false, false, true);
        assertThat(decision)
            .as("An account already linked to a different identity must refuse a second link")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.ALREADY_LINKED));
    }

    @Test
    void decide_linkDemotingLastAdministrator_denies() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(true, true, IdentityOwner.NONE, false, true, false, true);
        assertThat(decision)
            .as("A link whose group-derived role would demote the last administrator must be refused")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.ROLE_SYNC_REFUSED));
    }

    @Test
    void decide_identityAlreadyOwnedBySessionUser_isAnOrdinaryLogin() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(false, false, IdentityOwner.SESSION_USER, false, false, false, true);
        assertThat(decision)
            .as("A connect round trip for an already-linked identity is just a re-login")
            .isEqualTo(new OidcLoginDecision.UseExisting());
    }

    @Test
    void decide_reLoginDemotingLastAdministrator_denies() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(true, true, IdentityOwner.SESSION_USER, false, true, false, true);
        assertThat(decision)
            .as("Even the re-login path must refuse a last-administrator demotion")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.ROLE_SYNC_REFUSED));
    }

    @Test
    void decide_unclaimedIdentityAndUnlinkedAccount_links() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(true, true, IdentityOwner.NONE, false, false, false, true);
        assertThat(decision)
            .as("An unclaimed identity links to the signed-in account")
            .isEqualTo(new OidcLoginDecision.LinkToSessionUser());
    }

    @Test
    void decide_emailMismatch_deniesTheLink() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(false, false, IdentityOwner.NONE, false, false, false, false);
        assertThat(decision)
            .as("Completing the round trip with a different IdP account (email mismatch) must not link — the mistaken-account guard")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.LINK_EMAIL_MISMATCH));
    }

    @Test
    void decide_emailMissing_deniesTheLink() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(false, false, IdentityOwner.NONE, false, false, true, false);
        assertThat(decision)
            .as("Without an email claim the match cannot be verified, so the link is refused")
            .isEqualTo(new OidcLoginDecision.Deny(OidcDenialReason.EMAIL_MISSING));
    }

    @Test
    void decide_reLogin_ignoresTheEmailMatch() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(false, false, IdentityOwner.SESSION_USER, false, false, false, false);
        assertThat(decision)
            .as("The guard only applies to the link itself — an already-linked identity's re-login is resolved by issuer + subject")
            .isEqualTo(new OidcLoginDecision.UseExisting());
    }

    @Test
    void decide_groupCheckDisabled_doesNotRequireGroup() {
        final OidcLoginDecision decision = OidcLinkPolicy.decide(false, false, IdentityOwner.NONE, false, false, false, true);
        assertThat(decision)
            .as("Without group mapping configured, no group membership is required to link")
            .isEqualTo(new OidcLoginDecision.LinkToSessionUser());
    }
}
