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
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.zodac.diurnal.time.AppClock;

/**
 * Resolves a {@link SessionTokenAuthenticationRequest} into a {@link SecurityIdentity} by looking the
 * token up in the {@link SessionStore}. Runs the blocking database work on a worker thread, then
 * builds the identity with the account's live roles via {@link UserIdentities}.
 *
 * <p>
 * An unknown or expired token yields an {@link AuthenticationFailedException}, which drives the
 * mechanism's challenge (a {@code /login} redirect for the browser, {@code 401} for the API).
 */
@ApplicationScoped
public class SessionIdentityProvider implements IdentityProvider<SessionTokenAuthenticationRequest> {

    @Inject
    SessionStore sessionStore;

    @Inject
    AppClock clock;

    @Override
    public Class<SessionTokenAuthenticationRequest> getRequestType() {
        return SessionTokenAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(final SessionTokenAuthenticationRequest request, final AuthenticationRequestContext context) {
        return context.runBlocking(() -> resolve(request.getToken()));
    }

    private SecurityIdentity resolve(final String token) {
        return sessionStore.resolve(token, clock.now())
            .map(UserIdentities::of)
            .orElseThrow(AuthenticationFailedException::new);
    }
}
