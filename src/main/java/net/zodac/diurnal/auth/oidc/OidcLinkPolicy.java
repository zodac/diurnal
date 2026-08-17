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

/**
 * The pure decision core of the Settings "Connect {provider}" flow: when the OIDC callback arrives with a link-intent cookie AND a valid signed-in
 * session, {@link OidcUserProvisioner} gathers these facts and applies this policy instead of {@link OidcLoginPolicy}. The identity is attached by
 * its immutable issuer + subject pair, and the token's email must MATCH the signed-in account's email — not for security (the user has proven
 * control of both sides). But to catch the easy mistake of completing the round trip signed in to the WRONG identity-provider account, which would
 * silently bind a mismatched identity and (via the conversion) discard the password.
 */
public final class OidcLinkPolicy {

    /**
     * Who, if anyone, already owns the identity (issuer + subject pair) presented by the callback.
     */
    public enum IdentityOwner {

        /**
         * No local account is linked to this identity.
         */
        NONE,

        /**
         * The signed-in account itself is linked to this identity — an ordinary re-login, not a link.
         */
        SESSION_USER,

        /**
         * A different local account is linked to this identity.
         */
        OTHER_USER
    }

    private OidcLinkPolicy() {

    }

    /**
     * Decides the outcome of a link attempt.
     *
     * @param facts the gathered facts
     * @return the {@link OidcLoginDecision}
     */
    public static OidcLoginDecision decide(final OidcLinkFacts facts) {
        if (facts.groupCheckEnabled() && !facts.inConfiguredGroup()) {
            return new OidcLoginDecision.Deny(OidcDenialReason.NOT_IN_GROUP);
        }
        if (facts.identityOwner() == IdentityOwner.OTHER_USER) {
            return new OidcLoginDecision.Deny(OidcDenialReason.LINK_CONFLICT);
        }
        if (facts.identityOwner() == IdentityOwner.NONE && facts.sessionUserLinkedElsewhere()) {
            return new OidcLoginDecision.Deny(OidcDenialReason.ALREADY_LINKED);
        }
        if (facts.demotesLastAdministrator()) {
            return new OidcLoginDecision.Deny(OidcDenialReason.ROLE_SYNC_REFUSED);
        }
        if (facts.identityOwner() == IdentityOwner.SESSION_USER) {
            return new OidcLoginDecision.UseExisting();
        }
        // Only the actual link (not the re-login above) requires the email match — the mistaken-account guard.
        if (facts.emailMissing()) {
            return new OidcLoginDecision.Deny(OidcDenialReason.EMAIL_MISSING);
        }
        if (!facts.emailMatchesAccount()) {
            return new OidcLoginDecision.Deny(OidcDenialReason.LINK_EMAIL_MISMATCH);
        }
        return new OidcLoginDecision.LinkToSessionUser();
    }
}
