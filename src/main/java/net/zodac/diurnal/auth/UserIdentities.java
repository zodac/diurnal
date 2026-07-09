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

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import net.zodac.diurnal.user.User;

/**
 * Builds the {@link SecurityIdentity} for an authenticated {@link User}, so every auth path (session
 * resolution today, and any future one) grants the same principal, attributes and roles.
 *
 * <p>
 * The principal name is the user's email (resolved back to the {@code User} via
 * {@code SecurityIdentity.getPrincipal().getName()}); {@code userId} and {@code displayName} are
 * carried as attributes. Roles are derived from the live {@code User.role}, so they always reflect
 * the current database state.
 */
final class UserIdentities {

    private UserIdentities() {

    }

    /**
     * Builds a {@link SecurityIdentity} for the given user, granting {@code user} plus {@code admin}
     * when the account is an administrator.
     */
    static SecurityIdentity of(final User user) {
        final QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(user.email))
            .addAttribute("userId", user.id.toString())
            .addAttribute("displayName", user.displayName)
            .addRole(User.ROLE_USER);

        if (user.isAdmin()) {
            builder.addRole(User.ROLE_ADMIN);
        }
        return builder.build();
    }
}
