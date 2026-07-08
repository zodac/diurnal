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
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

class UserRowExtensionsTest {

    private static UserRow row(final String role) {
        return new UserRow(UUID.randomUUID(), "user@example.com", "Test User", role, "2026-01-01 00:00", "Never");
    }

    @Test
    void roleName_adminRole_returnsAdministrator() {
        assertThat(UserRowExtensions.roleName(row(User.ROLE_ADMIN)))
            .as("unexpected value")
            .isEqualTo("Administrator");
    }

    @Test
    void roleName_userRole_returnsUser() {
        assertThat(UserRowExtensions.roleName(row(User.ROLE_USER)))
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
}
