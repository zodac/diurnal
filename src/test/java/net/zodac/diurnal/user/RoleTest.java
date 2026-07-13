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

package net.zodac.diurnal.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RoleTest {

    // ── storageValue / displayName ──────────────────────────────────────────

    @Test
    void admin_hasExpectedStorageValueAndDisplayName() {
        assertThat(Role.ADMIN.storageValue())
            .as("unexpected stored value")
            .isEqualTo("admin");
        assertThat(Role.ADMIN.displayName())
            .as("unexpected display name")
            .isEqualTo("Administrator");
    }

    @Test
    void user_hasExpectedStorageValueAndDisplayName() {
        assertThat(Role.USER.storageValue())
            .as("unexpected stored value")
            .isEqualTo("user");
        assertThat(Role.USER.displayName())
            .as("unexpected display name")
            .isEqualTo("User");
    }

    // ── byDisplayName ───────────────────────────────────────────────────────

    @Test
    void byDisplayName_ordersRolesAlphabeticallyByDisplayName() {
        assertThat(Role.byDisplayName())
            .as("expected roles sorted alphabetically by display name")
            .containsExactly(Role.ADMIN, Role.USER)
            .extracting(Role::displayName)
            .as("expected display names in alphabetical order")
            .containsExactly("Administrator", "User");
    }

    @Test
    void byDisplayName_containsEveryRole() {
        assertThat(Role.byDisplayName())
            .as("expected every role in the catalogue")
            .containsExactlyInAnyOrder(Role.values());
    }

    // ── fromStorageValue ────────────────────────────────────────────────────

    @Test
    void fromStorageValue_adminValue_returnsAdmin() {
        assertThat(Role.fromStorageValue("admin"))
            .as("expected the admin role")
            .isEqualTo(Role.ADMIN);
    }

    @Test
    void fromStorageValue_userValue_returnsUser() {
        assertThat(Role.fromStorageValue("user"))
            .as("expected the user role")
            .isEqualTo(Role.USER);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"moderator", "ADMIN", "", "  admin  "})
    void fromStorageValue_unknownValue_defaultsToUser(final String value) {
        assertThat(Role.fromStorageValue(value))
            .as("expected an unrecognised value to default to the user role")
            .isEqualTo(Role.USER);
    }

    // ── isValid ─────────────────────────────────────────────────────────────

    @Test
    void isValid_knownValues_returnsTrue() {
        assertThat(Role.isValid("admin"))
            .as("expected the admin value to be valid")
            .isTrue();
        assertThat(Role.isValid("user"))
            .as("expected the user value to be valid")
            .isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"moderator", "ADMIN", "", "  user  "})
    void isValid_unknownValues_returnsFalse(final String value) {
        assertThat(Role.isValid(value))
            .as("expected an unrecognised value to be invalid")
            .isFalse();
    }
}
