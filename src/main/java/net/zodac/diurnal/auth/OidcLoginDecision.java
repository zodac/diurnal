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
 * The outcome of {@link OidcLoginPolicy#decide(OidcLoginFacts)}: {@link OidcUserProvisioner} switches exhaustively over it — continuing with the
 * linked account, provisioning a new one, or refusing the login with a {@link OidcDenialReason}.
 */
public sealed interface OidcLoginDecision
    permits OidcLoginDecision.UseExisting, OidcLoginDecision.ProvisionNew, OidcLoginDecision.LinkToSessionUser, OidcLoginDecision.AdoptByEmail,
    OidcLoginDecision.Deny {

    /**
     * A local account is already linked to this identity (issuer + subject) — authenticate as that account.
     */
    record UseExisting() implements OidcLoginDecision {

    }

    /**
     * No local account matches this identity or its email — provision a fresh account from the token's claims.
     */
    record ProvisionNew() implements OidcLoginDecision {

    }

    /**
     * The Settings "Connect" flow: attach the presented identity (issuer + subject) to the signed-in account, then authenticate as it. Only ever
     * produced by {@link OidcLinkPolicy}.
     */
    record LinkToSessionUser() implements OidcLoginDecision {

    }

    /**
     * The password-auth-disabled migration path: adopt the unlinked local account matched by email — attach the identity (issuer + subject) and
     * remove its password — then authenticate as it. Only ever produced when password authentication is disabled (the IdP is the deployment's sole
     * authority, so an email match there adds no new attack surface) and the email is not explicitly unverified.
     */
    record AdoptByEmail() implements OidcLoginDecision {

    }

    /**
     * The login is refused; {@link #reason()} selects the user-facing banner and the audit-log detail.
     *
     * @param reason why the login was refused
     */
    record Deny(OidcDenialReason reason) implements OidcLoginDecision {

    }
}
