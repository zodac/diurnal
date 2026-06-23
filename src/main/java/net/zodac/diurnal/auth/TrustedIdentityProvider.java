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

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import net.zodac.diurnal.user.User;

/**
 * Rebuilds the SecurityIdentity from the encrypted session cookie on each request.
 * Quarkus form auth stores the principal name in the cookie and issues a
 * TrustedAuthenticationRequest; this provider looks the user up in the DB to
 * reconstruct roles and attributes.
 */
@ApplicationScoped
public class TrustedIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    @Inject
    TrustedIdentityProvider self;

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            final TrustedAuthenticationRequest request,
            final AuthenticationRequestContext context) {
        final String email = request.getPrincipal();
        return context.runBlocking(() -> self.loadIdentity(email));
    }

    /**
     * Looks up the user by email and rebuilds their {@link SecurityIdentity} with roles/attributes.
     */
    @Transactional
    SecurityIdentity loadIdentity(final String email) {
        return User.findByEmail(email)
                .map(u -> {
                    final var builder = QuarkusSecurityIdentity.builder()
                            .setPrincipal(new QuarkusPrincipal(u.email))
                            .addAttribute("userId", u.id.toString())
                            .addAttribute("displayName", u.displayName)
                            .addRole(User.ROLE_USER);
                    if (u.isAdmin()) {
                        builder.addRole(User.ROLE_ADMIN);
                    }
                    return (SecurityIdentity) builder.build();
                })
                .orElseThrow(AuthenticationFailedException::new);
    }
}
