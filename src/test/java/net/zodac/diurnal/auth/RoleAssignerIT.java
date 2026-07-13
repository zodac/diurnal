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

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
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
    Optional<String> oidcAdminGroup = Optional.empty();

    @ConfigProperty(name = "oidc.user.group")
    Optional<String> oidcUserGroup = Optional.empty();

    // createDbState() not overridden — users table is empty after setUp()

    // ── roleForNewUser ────────────────────────────────────────────────────

    @Test
    void roleForNewUser_emptyTable_returnsAdmin() {
        runInTx(() -> assertThat(roleAssigner.roleForNewUser())
            .as("unexpected value")
            .isEqualTo(Role.ADMIN.storageValue()));
    }

    @Test
    void roleForNewUser_usersExist_returnsUser() {
        runInTx(() -> newUser("existing@lt.test", "Existing"));
        runInTx(() -> assertThat(roleAssigner.roleForNewUser())
            .as("unexpected value")
            .isEqualTo(Role.USER.storageValue()));
    }

    // ── roleFromOidcGroups — null / empty (always empty regardless of config) ──

    @Test
    void roleFromOidcGroups_nullList_returnsEmpty() {
        assertThat(roleAssigner.roleFromOidcGroups(null).isEmpty())
            .as("expected condition to be true")
            .isTrue();
    }

    @Test
    void roleFromOidcGroups_emptyList_returnsEmpty() {
        assertThat(roleAssigner.roleFromOidcGroups(List.of()).isEmpty())
            .as("expected condition to be true")
            .isTrue();
    }

    @Test
    void roleFromOidcGroups_groupNotInAnyConfiguredGroup_returnsEmpty() {
        // A random UUID can't match any configured group name
        assertThat(roleAssigner.roleFromOidcGroups(List.of("no-such-group-" + UUID.randomUUID())))
            .as("expected no matching role")
            .isEmpty();
    }

    // ── roleFromOidcGroups — environment-aware ────────────────────────────

    @Test
    void roleFromOidcGroups_configuredAdminGroup_returnsAdmin() {
        if (oidcAdminGroup.isPresent() && !oidcAdminGroup.get().isBlank()) {
            assertThat(roleAssigner.roleFromOidcGroups(List.of(oidcAdminGroup.get())))
                .as("unexpected value")
                .isEqualTo(Optional.of(Role.ADMIN.storageValue()));
        } else {
            // No admin group configured — even a group literally named "admin" returns empty
            assertThat(roleAssigner.roleFromOidcGroups(List.of("admin")).isEmpty())
                .as("expected condition to be true")
                .isTrue();
        }
    }

    @Test
    void roleFromOidcGroups_configuredUserGroup_returnsUser() {
        if (oidcUserGroup.isPresent() && !oidcUserGroup.get().isBlank()) {
            assertThat(roleAssigner.roleFromOidcGroups(List.of(oidcUserGroup.get())))
                .as("unexpected value")
                .isEqualTo(Optional.of(Role.USER.storageValue()));
        } else {
            assertThat(roleAssigner.roleFromOidcGroups(List.of("users")).isEmpty())
                .as("expected condition to be true")
                .isTrue();
        }
    }

    // ── isGroupCheckEnabled ───────────────────────────────────────────────

    @Test
    void isGroupCheckEnabled_matchesInjectedGroupConfig() {
        // Derives the expected value from the same config source the bean reads, so the
        // test passes in any environment (groups configured or not).
        final boolean expected = (oidcAdminGroup.isPresent() && !oidcAdminGroup.get().isBlank())
            || (oidcUserGroup.isPresent() && !oidcUserGroup.get().isBlank());
        assertThat(roleAssigner.isGroupCheckEnabled())
            .as("unexpected value")
            .isEqualTo(expected);
    }
}
