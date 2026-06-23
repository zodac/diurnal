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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

class UserRowExtensionsTest {

    private static UserRow row(final String role) {
        return new UserRow(UUID.randomUUID(), "user@example.com", "Test User", role, "2026-01-01 00:00", "Never");
    }

    @Test
    void roleName_adminRole_returnsAdministrator() {
        assertEquals("Administrator", UserRowExtensions.roleName(row(User.ROLE_ADMIN)), "unexpected value");
    }

    @Test
    void roleName_userRole_returnsUser() {
        assertEquals("User", UserRowExtensions.roleName(row(User.ROLE_USER)), "unexpected value");
    }

    @Test
    void roleName_unknownRole_returnsUser() {
        assertEquals("User", UserRowExtensions.roleName(row("moderator")), "unexpected value");
    }
}
