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
import java.util.List;
import java.util.Optional;
import net.zodac.diurnal.user.User;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

/**
 * Centralises role-assignment logic so all three user-creation paths
 * (password registration, OIDC provisioning) use the same rules.
 */
@ApplicationScoped
public class RoleAssigner {

    @ConfigProperty(name = "oidc.admin.group")
    Optional<String> oidcAdminGroup = Optional.empty();

    @ConfigProperty(name = "oidc.user.group")
    Optional<String> oidcUserGroup = Optional.empty();

    /**
     * Determines the role for a brand-new user created by password registration.
     * Must be called inside a transaction that holds a row-lock on the users table
     * (i.e. inside the same @Transactional as the INSERT) so the count is accurate.
     */
    public String roleForNewUser() {
        return User.count() == 0 ? User.ROLE_ADMIN : User.ROLE_USER;
    }

    /**
     * Returns true when at least one OIDC group is configured, meaning group membership
     * is the gate for access. A user not in any configured group should be denied.
     */
    public boolean isGroupCheckEnabled() {
        return (oidcAdminGroup.isPresent() && !oidcAdminGroup.get().isBlank())
            || (oidcUserGroup.isPresent() && !oidcUserGroup.get().isBlank());
    }

    /**
     * Maps IdP group membership to a local role.
     * Returns an empty Optional when neither group env var is configured nor the
     * user is not in either group (caller should fall back to another rule).
     */
    public final Optional<String> roleFromOidcGroups(@Nullable final List<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return Optional.empty();
        }
        if (oidcAdminGroup.isPresent() && !oidcAdminGroup.get().isBlank()
                && groups.contains(oidcAdminGroup.get())) {
            return Optional.of(User.ROLE_ADMIN);
        }
        if (oidcUserGroup.isPresent() && !oidcUserGroup.get().isBlank()
                && groups.contains(oidcUserGroup.get())) {
            return Optional.of(User.ROLE_USER);
        }
        return Optional.empty();
    }
}
