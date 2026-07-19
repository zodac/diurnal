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

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The reason an OIDC login was refused by {@link OidcLoginPolicy}. Each reason carries a stable {@link #code()} (persisted in the short-lived
 * {@code diurnal_oidc_error} cookie so the login page can show a specific banner after the error redirect) and the user-facing {@link #message}
 * shown in that banner.
 *
 * <p>
 * Messages are deliberately neutral: they never disclose whether an account exists beyond what the flow already implies, and operational detail
 * (e.g. <em>why</em> a role change was refused) stays in the server log — the user is only prompted to contact the application owner.
 */
public enum OidcDenialReason {

    /**
     * The very first account must be created locally through the setup flow, not provisioned by an identity provider.
     */
    SETUP_REQUIRED("setup-required", "The first account must be created locally before signing in with an identity provider."),

    /**
     * The ID token carried no usable email claim, so the local account cannot be resolved or provisioned.
     */
    EMAIL_MISSING("email-missing", "Your identity provider did not supply an email address. Please contact the application owner."),

    /**
     * The identity provider marked the email address as unverified ({@code email_verified: false}), so it must not be trusted to claim or create a
     * local account.
     */
    EMAIL_UNVERIFIED("email-unverified", "Your email address has not been verified by your identity provider. Please contact the application owner."),

    /**
     * Group→role mapping is configured and the user is in none of the configured groups.
     */
    NOT_IN_GROUP("not-in-group", "You are not authorised to access this service. Please contact the application owner."),

    /**
     * An unlinked local account already exists for the token's email address. Automatic email-based linking is deliberately not performed (an
     * attacker-controlled email claim must never claim an existing account); the sanctioned path is connecting the identity provider from the
     * Settings page while signed in locally.
     */
    ACCOUNT_EXISTS("account-exists",
        "An account with this email already exists. Sign in with your password, then connect {provider} from the Settings page."),

    /**
     * The group-synchronised role change could not be applied (the message deliberately does not explain the operational detail — the server log
     * carries it).
     */
    ROLE_SYNC_REFUSED("role-sync-refused",
        "Your access level could not be updated from your identity provider. Please contact the application owner."),

    /**
     * A Settings "Connect" attempt presented an identity that is already linked to a different local account.
     */
    LINK_CONFLICT("link-conflict", "This {provider} identity is already connected to a different account."),

    /**
     * A Settings "Connect" attempt presented an identity whose email does not match the signed-in account's email — most likely the user completed
     * the round trip signed in to the wrong identity-provider account, so nothing was connected.
     */
    LINK_EMAIL_MISMATCH("link-email-mismatch",
        "The {provider} account you signed in with uses a different email address than your account, so it was not connected. Sign in to "
            + "{provider} with the matching account and try again."),

    /**
     * A Settings "Connect" attempt was made by an account that is already linked to a different identity.
     */
    ALREADY_LINKED("already-linked", "Your account is already connected to {provider}.");

    private final String code;
    private final String template;

    OidcDenialReason(final String code, final String template) {
        this.code = code;
        this.template = template;
    }

    /**
     * The stable identifier carried in the {@code diurnal_oidc_error} cookie.
     *
     * @return the reason code
     */
    public String code() {
        return code;
    }

    /**
     * The user-facing banner message, with the {@code {provider}} placeholder (if any) replaced by the configured provider display name.
     *
     * @param providerName the identity provider's display name (e.g. {@code "Authelia"})
     * @return the message to render
     */
    public String message(final String providerName) {
        return template.replace("{provider}", providerName);
    }

    /**
     * Resolves a cookie value back to its {@link OidcDenialReason}.
     *
     * @param code the cookie value, possibly {@code null} or unrecognised
     * @return the matching reason, or {@link Optional#empty()} when the code is unknown
     */
    public static Optional<OidcDenialReason> fromCode(final @Nullable String code) {
        for (final OidcDenialReason reason : values()) {
            if (reason.code.equals(code)) {
                return Optional.of(reason);
            }
        }
        return Optional.empty();
    }
}
