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

/**
 * The pure decision core of OIDC sign-in: given the {@link OidcLoginFacts} gathered by {@link OidcUserProvisioner}, decides whether the login
 * continues with the linked account, provisions a new one, or is refused — and why. All branching lives here (unit-tested to full mutation
 * strength); the provisioner is glue that gathers facts and applies the decision.
 *
 * <p>
 * The key security property: a local account is only ever resolved by the immutable issuer + subject pair. An email match with an unlinked local
 * account is a refusal ({@link OidcDenialReason#ACCOUNT_EXISTS}), never an automatic link — the email claim is attacker-influenceable at many
 * identity providers, and silently linking on it is a known account-takeover vector (cf. Grafana CVE-2023-3128). Linking is an explicit,
 * authenticated act from the Settings page instead.
 */
public final class OidcLoginPolicy {

    private OidcLoginPolicy() {

    }

    /**
     * The revocation guard for OIDC-authenticated requests: the Quarkus {@code q_session} cookie alone must never grant access to a page, or the
     * server-side session store ("log out from everywhere") could not revoke an OIDC user's other devices. Outside the code-flow callback (where
     * the Diurnal session has not been minted yet) the request must therefore also carry a live {@code diurnal_session} resolving to the same user;
     * when it does not, authentication fails and the code flow re-runs, re-minting a session at the callback.
     *
     * @param atCallbackPath         the request is the code-flow callback itself
     * @param liveSessionForSameUser the request carries a valid server-side session belonging to the authenticated user
     * @return {@code true} when the request may proceed
     */
    public static boolean revocationGuardSatisfied(final boolean atCallbackPath, final boolean liveSessionForSameUser) {
        return atCallbackPath || liveSessionForSameUser;
    }

    /**
     * Decides the outcome of an OIDC sign-in.
     *
     * <p>
     * The checks run in a deliberate order: the first-run bootstrap guard, then the claim sanity checks (email present), then authorisation
     * (configured-group membership), then account resolution — a linked account wins (refusing a group-synchronised demotion of the last
     * administrator), an email collision with an unlinked account refuses, and only a verified (or unstated) email may provision a new account.
     *
     * @param facts the gathered facts
     * @return the {@link OidcLoginDecision}
     */
    public static OidcLoginDecision decide(final OidcLoginFacts facts) {
        if (facts.firstUserBootstrapBlocked()) {
            return new OidcLoginDecision.Deny(OidcDenialReason.SETUP_REQUIRED);
        }
        if (facts.emailMissing()) {
            return new OidcLoginDecision.Deny(OidcDenialReason.EMAIL_MISSING);
        }
        if (facts.groupCheckEnabled() && !facts.inConfiguredGroup()) {
            return new OidcLoginDecision.Deny(OidcDenialReason.NOT_IN_GROUP);
        }
        if (facts.linkedAccountFound()) {
            if (facts.demotesLastAdministrator()) {
                return new OidcLoginDecision.Deny(OidcDenialReason.ROLE_SYNC_REFUSED);
            }
            return new OidcLoginDecision.UseExisting();
        }
        // With password auth enabled an email collision must refuse (the sanctioned path is Settings → Connect); with it disabled that path does
        // not exist and the IdP is the deployment's sole authority anyway, so a verified email adopts the account — subject to the last-admin
        // guard, since adoption applies the IdP-derived role like any other login.
        if (facts.emailCollision() && facts.passwordAuthEnabled()) {
            return new OidcLoginDecision.Deny(OidcDenialReason.ACCOUNT_EXISTS);
        }
        if (Boolean.FALSE.equals(facts.emailVerified())) {
            return new OidcLoginDecision.Deny(OidcDenialReason.EMAIL_UNVERIFIED);
        }
        if (facts.emailCollision()) {
            if (facts.demotesLastAdministrator()) {
                return new OidcLoginDecision.Deny(OidcDenialReason.ROLE_SYNC_REFUSED);
            }
            return new OidcLoginDecision.AdoptByEmail();
        }
        return new OidcLoginDecision.ProvisionNew();
    }
}
