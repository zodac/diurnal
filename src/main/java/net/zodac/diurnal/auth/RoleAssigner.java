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
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import net.zodac.diurnal.auth.oidc.OidcConfig;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.jspecify.annotations.Nullable;

/**
 * Centralises role-assignment logic so all user-creation paths (password registration, OIDC provisioning) use the same rules.
 */
@ApplicationScoped
public class RoleAssigner {

    private final OidcConfig oidcConfig;

    /**
     * Injects the application OIDC policy settings.
     *
     * @param oidcConfig the application OIDC policy settings
     */
    @Inject
    public RoleAssigner(final OidcConfig oidcConfig) {
        this.oidcConfig = oidcConfig;
    }

    /**
     * Determines the role for a brand-new user created by password registration.
     *
     * @return the new {@link User}'s role
     */
    public String roleForNewUser() {
        return User.count() == 0L ? Role.ADMIN.storageValue() : Role.USER.storageValue();
    }

    /**
     * Checking if at least one OIDC group is configured, meaning group membership should be considered.
     *
     * @return {@code true} when at least one OIDC group is configured
     */
    public boolean isGroupCheckEnabled() {
        final Optional<String> adminGroup = oidcConfig.adminGroup();
        final Optional<String> userGroup = oidcConfig.userGroup();
        return (adminGroup.isPresent() && !adminGroup.get().isBlank()) || (userGroup.isPresent() && !userGroup.get().isBlank());
    }

    /**
     * Maps IdP group membership to a local role.
     *
     * @return {@link Optional#empty()} when neither group env var is configured nor the user is not in either group
     */
    public Optional<String> roleFromOidcGroups(@Nullable final List<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return Optional.empty();
        }

        final Optional<String> adminGroup = oidcConfig.adminGroup();
        if (adminGroup.isPresent() && !adminGroup.get().isBlank()
            && groups.contains(adminGroup.get())) {
            return Optional.of(Role.ADMIN.storageValue());
        }

        final Optional<String> userGroup = oidcConfig.userGroup();
        if (userGroup.isPresent() && !userGroup.get().isBlank()
            && groups.contains(userGroup.get())) {
            return Optional.of(Role.USER.storageValue());
        }
        return Optional.empty();
    }
}
