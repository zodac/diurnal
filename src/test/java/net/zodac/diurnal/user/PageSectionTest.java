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

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PageSectionTest {

    @Test
    void values_areTheOfferedSections_inDisplayOrder() {
        final List<String> expected = List.of(
            "dashboard",
            "actions",
            "notes",
            "stats",
            "users");
        assertThat(Arrays.stream(PageSection.values()).map(PageSection::key).toList())
            .as("unexpected sections (declaration order is the Settings display order AND the stored order)")
            .containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @EnumSource(PageSection.class)
    void fromKey_ownKey_resolvesBackToTheSection(final PageSection section) {
        assertThat(PageSection.fromKey(section.key()))
            .as("unexpected value")
            .contains(section);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "DASHBOARD", "Dashboard", "dashboards", "logs", "admin"})
    void fromKey_unknownKey_isEmpty(final String key) {
        assertThat(PageSection.fromKey(key))
            .as("expected an unrecognised section key to resolve to nothing")
            .isEmpty();
    }

    @Test
    void fromKey_null_isEmpty() {
        assertThat(PageSection.fromKey(null))
            .as("expected a null section key to resolve to nothing")
            .isEmpty();
    }

    @Test
    void adminOnly_isSetForTheAdminConsoleListOnly() {
        assertThat(Arrays.stream(PageSection.values()).filter(PageSection::adminOnly).toList())
            .as("only the admin console's own lists are administrator-only")
            .containsExactly(PageSection.USERS);
    }
}
