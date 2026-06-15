package net.zodac.diurnal.auth;

import net.zodac.diurnal.user.User;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Centralises role-assignment logic so all three user-creation paths
 * (password registration, OIDC provisioning) use the same rules.
 */
@ApplicationScoped
public class RoleAssigner {

    @ConfigProperty(name = "oidc.admin.group")
    Optional<String> oidcAdminGroup;

    @ConfigProperty(name = "oidc.user.group")
    Optional<String> oidcUserGroup;

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
    public Optional<String> roleFromOidcGroups(List<String> groups) {
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
