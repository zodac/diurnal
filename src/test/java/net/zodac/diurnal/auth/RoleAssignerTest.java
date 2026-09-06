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

import java.util.List;
import java.util.Optional;
import net.zodac.diurnal.stub.StubOidcConfig;
import net.zodac.diurnal.user.Role;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The IdP-group half of {@link RoleAssigner}, pinned against a stubbed {@link net.zodac.diurnal.auth.oidc.OidcConfig} rather than the deployment's.
 *
 * <p>
 * {@code RoleAssignerIT} exercises the same two methods against the INJECTED config, which is whatever the environment happens to carry - so each of
 * its group-aware assertions branches on whether a group is configured at all, and the configured-group arms never run in a deployment that has
 * none. Stubbing the config is what lets every combination be asserted unconditionally; {@code roleForNewUser} stays in the IT, since it counts rows.
 */
class RoleAssignerTest {

    private static final String ADMIN_GROUP = "diurnal-admins";
    private static final String USER_GROUP = "diurnal-users";

    // Takes the two group names as plain nullable strings rather than as Optionals - the config mapping's own shape - because an Optional
    // parameter is a Qodana finding, and null here reads as the unset env var it stands for.
    private static RoleAssigner assignerWith(final @Nullable String adminGroup, final @Nullable String userGroup) {
        final StubOidcConfig config =
            new StubOidcConfig("stub", false, false, Optional.empty(), Optional.ofNullable(adminGroup), Optional.ofNullable(userGroup));
        return new RoleAssigner(config);
    }

    @Test
    void isGroupCheckEnabled_noGroupsConfigured_isFalse() {
        assertThat(assignerWith(null, null).isGroupCheckEnabled())
            .as("with neither group configured, group membership is not consulted at all")
            .isFalse();
    }

    @Test
    void isGroupCheckEnabled_blankGroupsConfigured_isFalse() {
        // An env var set to the empty string is present but means nothing - it must read exactly as an absent one.
        assertThat(assignerWith(" ", "").isGroupCheckEnabled())
            .as("a blank group name is no group at all")
            .isFalse();
    }

    @Test
    void isGroupCheckEnabled_adminGroupOnly_isTrue() {
        assertThat(assignerWith(ADMIN_GROUP, null).isGroupCheckEnabled())
            .as("an admin group alone is enough to make group membership meaningful")
            .isTrue();
    }

    @Test
    void isGroupCheckEnabled_userGroupOnly_isTrue() {
        assertThat(assignerWith(null, USER_GROUP).isGroupCheckEnabled())
            .as("a user group alone is enough to make group membership meaningful")
            .isTrue();
    }

    @Test
    void roleFromOidcGroups_nullGroups_isEmpty() {
        assertThat(assignerWith(ADMIN_GROUP, USER_GROUP).roleFromOidcGroups(null))
            .as("a token carrying no groups claim maps to no role")
            .isEmpty();
    }

    @Test
    void roleFromOidcGroups_noGroups_isEmpty() {
        assertThat(assignerWith(ADMIN_GROUP, USER_GROUP).roleFromOidcGroups(List.of()))
            .as("a token carrying an empty groups claim maps to no role")
            .isEmpty();
    }

    @Test
    void roleFromOidcGroups_memberOfTheAdminGroup_isAdmin() {
        assertThat(assignerWith(ADMIN_GROUP, USER_GROUP).roleFromOidcGroups(List.of("unrelated", ADMIN_GROUP)))
            .as("membership of the configured admin group maps to the administrator role")
            .contains(Role.ADMIN.storageValue());
    }

    @Test
    void roleFromOidcGroups_memberOfBothGroups_isAdmin() {
        assertThat(assignerWith(ADMIN_GROUP, USER_GROUP).roleFromOidcGroups(List.of(USER_GROUP, ADMIN_GROUP)))
            .as("the admin group is checked first, so it wins for a member of both")
            .contains(Role.ADMIN.storageValue());
    }

    @Test
    void roleFromOidcGroups_memberOfTheUserGroupOnly_isUser() {
        assertThat(assignerWith(ADMIN_GROUP, USER_GROUP).roleFromOidcGroups(List.of(USER_GROUP)))
            .as("membership of the configured user group maps to the ordinary role")
            .contains(Role.USER.storageValue());
    }

    @Test
    void roleFromOidcGroups_memberOfNeitherGroup_isEmpty() {
        assertThat(assignerWith(ADMIN_GROUP, USER_GROUP).roleFromOidcGroups(List.of("some-other-group")))
            .as("groups the deployment does not name map to no role")
            .isEmpty();
    }

    @Test
    void roleFromOidcGroups_blankGroupConfig_neverMatches() {
        // The guard that matters: without the isBlank() checks, a blank configured name would be compared against the claim's own values, and a
        // token carrying an empty group name would be handed the administrator role.
        assertThat(assignerWith("", "").roleFromOidcGroups(List.of("", ADMIN_GROUP, USER_GROUP)))
            .as("a blank configured group must match nothing, not the empty group name in the claim")
            .isEmpty();
    }
}
