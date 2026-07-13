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

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards that the public API's {@link UserDto.Preferences} projection stays in lock-step with the {@link Preference}-annotated fields on
 * {@link User}. Adding a new preference column without exposing it through {@code GET /api/users/me} — the recurring de-sync this guard exists to
 * prevent — fails here.
 */
class UserPreferencesExposureTest {

    @Test
    void everyPreferenceFieldIsExposedByTheDto() {
        final List<String> preferenceFields = Arrays.stream(User.class.getDeclaredFields())
            .filter(field -> field.isAnnotationPresent(Preference.class))
            .map(Field::getName)
            .toList();

        final List<String> exposedFields = Arrays.stream(UserDto.Preferences.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();

        assertThat(preferenceFields)
            .as("no @Preference fields were found on User — the marker or the reflection query is broken")
            .isNotEmpty();

        assertThat(exposedFields)
            .as("every @Preference field on User must be exposed by UserDto.Preferences (same name), and the DTO "
                    + "must expose nothing that is not a @Preference field")
            .containsExactlyInAnyOrderElementsOf(preferenceFields);
    }
}
