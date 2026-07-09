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

package net.zodac.diurnal.user;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the {@link User} entity for the currently-authenticated principal, centralising the
 * lookup that every resource previously repeated inline.
 *
 * <p>The identity built by session auth (the {@code diurnal_session} cookie or a Bearer token, via
 * {@code UserIdentities}) and by the OIDC flow all carry a {@code userId} attribute, so the account is
 * fetched by primary key; absent that attribute it falls back to the email — the principal name. Within
 * a single request the result is served from Hibernate's first-level cache, so resolving the user more
 * than once issues no extra query.
 */
@ApplicationScoped
public class CurrentUser {

    @Inject
    SecurityIdentity identity;

    /**
     * Finds the authenticated user, or an empty {@link Optional} if the account no longer exists.
     */
    public Optional<User> find() {
        final String userId = identity.getAttribute("userId");
        if (userId != null) {
            return User.findByIdOptional(UUID.fromString(userId));
        }
        return User.findByEmail(identity.getPrincipal().getName());
    }

    /**
     * Returns the authenticated user, throwing {@link NoSuchElementException} if the account no longer exists.
     */
    public User get() {
        return find().orElseThrow();
    }
}
