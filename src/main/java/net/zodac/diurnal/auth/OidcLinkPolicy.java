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
 * The pure decision core of the Settings "Connect {provider}" flow: when the OIDC callback arrives with a link-intent cookie AND a valid signed-in
 * session, {@link OidcUserProvisioner} gathers these facts and applies this policy instead of {@link OidcLoginPolicy}. The identity is attached by
 * its immutable issuer + subject pair, and the token's email must MATCH the signed-in account's email — not for security (the user has proven
 * control of both sides), but to catch the easy mistake of completing the round trip signed in to the WRONG identity-provider account, which would
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
     * @param groupCheckEnabled          at least one OIDC group→role mapping is configured
     * @param inConfiguredGroup          the token's groups claim matched a configured group
     * @param identityOwner              who already owns the presented identity
     * @param sessionUserLinkedElsewhere the signed-in account is already linked to a <em>different</em> identity
     * @param demotesLastAdministrator   applying the IdP-derived role to the signed-in account would demote the final remaining administrator
     * @param emailMissing               the token carried no usable email claim, so the match below cannot be checked
     * @param emailMatchesAccount        the token's email equals the signed-in account's email
     * @return the {@link OidcLoginDecision}
     */
    public static OidcLoginDecision decide(final boolean groupCheckEnabled, final boolean inConfiguredGroup, final IdentityOwner identityOwner,
        final boolean sessionUserLinkedElsewhere, final boolean demotesLastAdministrator, final boolean emailMissing,
        final boolean emailMatchesAccount) {
        if (groupCheckEnabled && !inConfiguredGroup) {
            return new OidcLoginDecision.Deny(OidcDenialReason.NOT_IN_GROUP);
        }
        if (identityOwner == IdentityOwner.OTHER_USER) {
            return new OidcLoginDecision.Deny(OidcDenialReason.LINK_CONFLICT);
        }
        if (identityOwner == IdentityOwner.NONE && sessionUserLinkedElsewhere) {
            return new OidcLoginDecision.Deny(OidcDenialReason.ALREADY_LINKED);
        }
        if (demotesLastAdministrator) {
            return new OidcLoginDecision.Deny(OidcDenialReason.ROLE_SYNC_REFUSED);
        }
        if (identityOwner == IdentityOwner.SESSION_USER) {
            return new OidcLoginDecision.UseExisting();
        }
        // Only the actual link (not the re-login above) requires the email match — the mistaken-account guard.
        if (emailMissing) {
            return new OidcLoginDecision.Deny(OidcDenialReason.EMAIL_MISSING);
        }
        if (!emailMatchesAccount) {
            return new OidcLoginDecision.Deny(OidcDenialReason.LINK_EMAIL_MISMATCH);
        }
        return new OidcLoginDecision.LinkToSessionUser();
    }
}
