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

package net.zodac.diurnal.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.zodac.diurnal.user.Preference;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Guards that the export archive stays in lock-step with the {@link Preference}-annotated fields on {@link User} - the same de-sync
 * {@code UserPreferencesExposureTest} prevents between the entity and the public API, now for the third surface a preference has to reach.
 *
 * <p>
 * An export calls itself a backup, so a preference that is not in the archive is one a restore silently loses. Adding a new preference column
 * without giving it a {@link SettingKey} fails here.
 */
class SettingsAreTransferableTest {

    // The two preferences a single settings.csv ROW cannot hold, because each is a SET of values rather than one. They are carried as their own
    // families of rows instead - see TransferFiles.PAGE_SIZE_PREFIX and TransferFiles.STAT_PREFIX - so they have no SettingKey, and are named here
    // rather than left as a hole in the comparison below.
    private static final List<String> SET_VALUED_PREFERENCES = List.of("pageSizes", "statsFields");

    // The one setting the archive carries that is NOT a @Preference: the display name is profile rather than preference (see that annotation's own
    // Javadoc), and is carried because a restore that brings back ten years of journal but not what the account calls itself is not a restore. It
    // is listed here rather than silently tolerated, so that a SECOND identity column appearing in the archive has to be a deliberate edit.
    private static final List<String> PROFILE_SETTINGS = List.of("displayName");

    @Test
    void everyPreferenceFieldIsCarriedByTheArchive() {
        final List<String> preferenceFields = Arrays.stream(User.class.getDeclaredFields())
            .filter(field -> field.isAnnotationPresent(Preference.class))
            .map(Field::getName)
            .toList();

        final List<String> carried = new ArrayList<>(SET_VALUED_PREFERENCES);
        for (final SettingKey setting : SettingKey.values()) {
            if (!PROFILE_SETTINGS.contains(setting.key())) {
                carried.add(setting.key());
            }
        }

        assertThat(preferenceFields)
            .as("no @Preference fields were found on User - the marker or the reflection query is broken")
            .isNotEmpty();

        assertThat(carried)
            .as("every @Preference field on User must be carried by settings.csv - either as a SettingKey of the same name, or as one of the two "
                + "set-valued preferences written as a family of rows - and the archive must carry nothing that is not one, beyond the profile "
                + "settings named above")
            .containsExactlyInAnyOrderElementsOf(preferenceFields);
    }

    @Test
    void everySettingKeyIsNamedAfterTheFieldItCarries() {
        // The key is deliberately the User field's own name, which is also the name GET /api/v1/users/me exposes it under: one vocabulary for
        // someone editing the archive and someone reading the API, rather than a third spelling with nothing to check it against.
        final List<String> userFields = Arrays.stream(User.class.getDeclaredFields())
            .map(Field::getName)
            .toList();

        for (final SettingKey setting : SettingKey.values()) {
            assertThat(userFields)
                .as("the settings.csv key '%s' must name a field on User", setting.key())
                .contains(setting.key());
        }
    }

    @Test
    void noCredentialOrRoleColumnIsCarried() {
        // An import must never be a route to changing WHO an account is. The display name is deliberately carried; these are deliberately not, and
        // adding one would be a privilege-escalation or account-takeover path rather than a restore.
        final List<String> carried = Arrays.stream(SettingKey.values())
            .map(SettingKey::key)
            .toList();

        assertThat(carried)
            .as("the archive must not carry an identity, credential or role column")
            .doesNotContain("email", "passwordHash", "oidcSubject", "oidcIssuer", "role", "lastLoginAt", "id");
    }
}
