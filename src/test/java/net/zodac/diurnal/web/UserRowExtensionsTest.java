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

package net.zodac.diurnal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

class UserRowExtensionsTest {

    private static UserRow row(final String role) {
        return row(role, "local");
    }

    private static UserRow row(final String role, final String authSource) {
        return new UserRow(UUID.randomUUID(), "user@example.com", "Test User", role, authSource, "2026-01-01 00:00", "Never", "UTC");
    }

    @Test
    void roleName_adminRole_returnsAdministrator() {
        assertThat(UserRowExtensions.roleName(row(Role.ADMIN.storageValue())))
            .as("unexpected value")
            .isEqualTo("Administrator");
    }

    @Test
    void roleName_userRole_returnsUser() {
        assertThat(UserRowExtensions.roleName(row(Role.USER.storageValue())))
            .as("unexpected value")
            .isEqualTo("User");
    }

    @Test
    void roleName_unknownRole_returnsUser() {
        assertThat(UserRowExtensions.roleName(row("moderator")))
            .as("unexpected value")
            .isEqualTo("User");
    }

    @Test
    void options_returnsRoleCatalogueOrderedByDisplayName() {
        assertThat(UserRowExtensions.options())
            .as("expected the role picker catalogue, alphabetically by display name")
            .containsExactly(Role.ADMIN, Role.USER);
    }

    @Test
    void authSourceLabel_localSource_returnsLocal() {
        assertThat(UserRowExtensions.authSourceLabel(row(Role.USER.storageValue(), "local")))
            .as("unexpected value")
            .isEqualTo("Local");
    }

    @Test
    void authSourceLabel_oidcSource_returnsOidc() {
        assertThat(UserRowExtensions.authSourceLabel(row(Role.USER.storageValue(), "oidc")))
            .as("unexpected value")
            .isEqualTo("OIDC");
    }

    @Test
    void authSourceLabel_hybridSource_returnsBoth() {
        assertThat(UserRowExtensions.authSourceLabel(row(Role.USER.storageValue(), "local+oidc")))
            .as("unexpected value")
            .isEqualTo("Local + OIDC");
    }

    @Test
    void authSourceLabel_unknownSource_fallsBackToLocal() {
        assertThat(UserRowExtensions.authSourceLabel(row(Role.USER.storageValue(), "saml")))
            .as("unexpected value")
            .isEqualTo("Local");
    }

    @Test
    void zoneTooltip_prefixesZoneLabel() {
        final UserRow row = new UserRow(UUID.randomUUID(), "user@example.com", "Test User",
            Role.USER.storageValue(), "local", "2026-01-01 00:00", "Never", "Europe/London");

        assertThat(UserRowExtensions.zoneTooltip(row))
            .as("unexpected timezone tooltip label")
            .isEqualTo("Timezone: Europe/London");
    }
}
