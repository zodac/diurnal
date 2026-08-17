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
 * The facts {@link OidcLinkPolicy#decide(OidcLinkFacts)} branches on, gathered by {@link OidcUserProvisioner} from the ID-token claims, the
 * configuration and the database. A pure data carrier so the decision logic stays static and unit-testable, and the twin of {@link OidcLoginFacts}
 * for the Settings "Connect {provider}" flow.
 *
 * @param groupCheckEnabled          at least one OIDC group&rarr;role mapping is configured
 * @param inConfiguredGroup          the token's groups claim matched a configured group (i.e. an IdP-derived role is available)
 * @param identityOwner              who already owns the presented identity (issuer + subject pair)
 * @param sessionUserLinkedElsewhere the signed-in account is already linked to a <em>different</em> identity
 * @param demotesLastAdministrator   applying the IdP-derived role to the signed-in account would demote the final remaining administrator
 * @param emailMissing               the token carried no usable email claim, so the match below cannot be checked
 * @param emailMatchesAccount        the token's email equals the signed-in account's email
 */
public record OidcLinkFacts(
    boolean groupCheckEnabled,
    boolean inConfiguredGroup,
    OidcLinkPolicy.IdentityOwner identityOwner,
    boolean sessionUserLinkedElsewhere,
    boolean demotesLastAdministrator,
    boolean emailMissing,
    boolean emailMatchesAccount) {

}
