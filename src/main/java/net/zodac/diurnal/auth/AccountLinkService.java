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

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Objects;
import java.util.Optional;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The single owner of attaching an identity-provider identity to a local account — the Settings "Connect" flow and the password-auth-disabled
 * email adoption, both applied during the OIDC callback by {@link OidcUserProvisioner}.
 *
 * <p>
 * Connecting is a one-way conversion: the account's password is removed in the same step, so a linked account signs in through the identity
 * provider ONLY (there is no hybrid state and no disconnect — migration {@code V22} normalises pre-existing rows the same way). The password
 * removal is deliberate: two permanently-live credentials would double the account's attack surface and make "which login rules apply?" ambiguous,
 * and the IdP is the stronger authority once trusted.
 *
 * <p>
 * Callers own the transaction (each endpoint is {@code @Transactional}); this bean only assumes one is active.
 */
@ApplicationScoped
public class AccountLinkService {

    private static final Logger LOGGER = LogManager.getLogger(AccountLinkService.class);

    /**
     * Attaches the identity provider's issuer + subject pair to the account and removes its password, converting it to identity-provider-only
     * sign-in. The caller ({@link OidcUserProvisioner}, applying an {@code OidcLinkPolicy}/{@code OidcLoginPolicy} decision) has already
     * established that the identity is unclaimed and the account unlinked; this method re-checks both defensively and persists the conversion.
     *
     * @param user    the account to link
     * @param issuer  the identity provider's issuer
     * @param subject the identity provider's subject for this user
     */
    public void link(final User user, final String issuer, final String subject) {
        if (user.oidcSubject != null && !user.oidcSubject.isBlank()) {
            throw new IllegalStateException("Account " + user.email + " is already linked to an identity provider");
        }
        final Optional<User> owner = User.findByOidc(issuer, subject);
        if (owner.isPresent() && !Objects.equals(owner.get().id, user.id)) {
            throw new IllegalStateException("OIDC identity is already linked to another account");
        }
        user.oidcIssuer = issuer;
        user.oidcSubject = subject;
        user.passwordHash = null; // NOPMD: NullAssignment - the conversion to OIDC-only sign-in IS the operation
        user.persist();
        LOGGER.warn("Connected identity provider to account {} - password removed, the account now signs in via the IdP only (iss={})",
            user.email, issuer);
    }
}
