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

import org.jspecify.annotations.Nullable;

/**
 * The facts {@link OidcLoginPolicy#decide(OidcLoginFacts)} branches on, gathered by {@link OidcUserProvisioner} from the ID-token claims, the
 * configuration and the database. A pure data carrier so the decision logic stays static and unit-testable.
 *
 * @param firstUserBootstrapBlocked no user exists yet — the initial account must always be created via the local setup flow, never provisioned here
 * @param passwordAuthEnabled       password authentication is enabled in this deployment (decides whether an email collision can be adopted)
 * @param emailMissing              the token carried no usable email claim
 * @param groupCheckEnabled         at least one OIDC group→role mapping is configured
 * @param inConfiguredGroup         the token's groups claim matched a configured group (i.e. an IdP-derived role is available)
 * @param linkedAccountFound        a local account is already linked to this identity (matched by issuer + subject)
 * @param demotesLastAdministrator  applying the IdP-derived role to the matched account (linked, or the email match) would demote the final
 *                                  remaining administrator
 * @param emailCollision            an unlinked local account already exists for the token's email address
 * @param emailVerified             the {@code email_verified} claim; {@code null} when the provider does not emit it
 */
public record OidcLoginFacts(
    boolean firstUserBootstrapBlocked,
    boolean passwordAuthEnabled,
    boolean emailMissing,
    boolean groupCheckEnabled,
    boolean inConfiguredGroup,
    boolean linkedAccountFound,
    boolean demotesLastAdministrator,
    boolean emailCollision,
    @Nullable Boolean emailVerified) {

}
