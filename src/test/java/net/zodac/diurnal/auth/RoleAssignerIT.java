package net.zodac.diurnal.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

@QuarkusTest
class RoleAssignerIT extends IntegrationTestBase {

    @Inject
    RoleAssigner roleAssigner;

    // Mirror the same config the bean reads so tests stay environment-agnostic.
    // SmallRye Config reads .env at higher priority than %test profile overrides, so
    // we can't force a specific value here — instead we derive the expected result from
    // the same config source the bean uses and verify the two are consistent.
    @ConfigProperty(name = "oidc.admin.group")
    Optional<String> oidcAdminGroup;

    @ConfigProperty(name = "oidc.user.group")
    Optional<String> oidcUserGroup;

    // createDbState() not overridden — users table is empty after setUp()

    // ── roleForNewUser ────────────────────────────────────────────────────

    @Test
    void roleForNewUser_emptyTable_returnsAdmin() {
        runInTx(() -> assertEquals(User.ROLE_ADMIN, roleAssigner.roleForNewUser()));
    }

    @Test
    void roleForNewUser_usersExist_returnsUser() {
        runInTx(() -> newUser("existing@lt.test", "Existing"));
        runInTx(() -> assertEquals(User.ROLE_USER, roleAssigner.roleForNewUser()));
    }

    // ── roleFromOidcGroups — null / empty (always empty regardless of config) ──

    @Test
    void roleFromOidcGroups_nullList_returnsEmpty() {
        assertTrue(roleAssigner.roleFromOidcGroups(null).isEmpty());
    }

    @Test
    void roleFromOidcGroups_emptyList_returnsEmpty() {
        assertTrue(roleAssigner.roleFromOidcGroups(List.of()).isEmpty());
    }

    @Test
    void roleFromOidcGroups_groupNotInAnyConfiguredGroup_returnsEmpty() {
        // A random UUID can't match any configured group name
        assertTrue(roleAssigner.roleFromOidcGroups(List.of("no-such-group-" + UUID.randomUUID())).isEmpty());
    }

    // ── roleFromOidcGroups — environment-aware ────────────────────────────

    @Test
    void roleFromOidcGroups_configuredAdminGroup_returnsAdmin() {
        if (oidcAdminGroup.isPresent() && !oidcAdminGroup.get().isBlank()) {
            assertEquals(Optional.of(User.ROLE_ADMIN),
                    roleAssigner.roleFromOidcGroups(List.of(oidcAdminGroup.get())));
        } else {
            // No admin group configured — even a group literally named "admin" returns empty
            assertTrue(roleAssigner.roleFromOidcGroups(List.of("admin")).isEmpty());
        }
    }

    @Test
    void roleFromOidcGroups_configuredUserGroup_returnsUser() {
        if (oidcUserGroup.isPresent() && !oidcUserGroup.get().isBlank()) {
            assertEquals(Optional.of(User.ROLE_USER),
                    roleAssigner.roleFromOidcGroups(List.of(oidcUserGroup.get())));
        } else {
            assertTrue(roleAssigner.roleFromOidcGroups(List.of("users")).isEmpty());
        }
    }

    // ── isGroupCheckEnabled ───────────────────────────────────────────────

    @Test
    void isGroupCheckEnabled_matchesInjectedGroupConfig() {
        // Derives the expected value from the same config source the bean reads, so the
        // test passes in any environment (groups configured or not).
        boolean expected = (oidcAdminGroup.isPresent() && !oidcAdminGroup.get().isBlank())
                || (oidcUserGroup.isPresent() && !oidcUserGroup.get().isBlank());
        assertEquals(expected, roleAssigner.isGroupCheckEnabled());
    }
}
