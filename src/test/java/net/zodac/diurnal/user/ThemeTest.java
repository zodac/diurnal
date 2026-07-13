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

class ThemeTest {

    // ── Constant metadata ───────────────────────────────────────────────────

    @Test
    void system_hasExpectedMetadata() {
        assertThat(Theme.SYSTEM.value())
            .as("unexpected value")
            .isEqualTo("system");
        assertThat(Theme.SYSTEM.label())
            .as("unexpected label")
            .isEqualTo("System");
        assertThat(Theme.SYSTEM.title())
            .as("unexpected title")
            .isEqualTo("System theme");
        assertThat(Theme.SYSTEM.alt())
            .as("unexpected alt text")
            .isEqualTo("Dashboard split diagonally between light and dark themes");
        assertThat(Theme.SYSTEM.previewImage())
            .as("unexpected preview image")
            .isEqualTo("page-nova-full-system");
    }

    @Test
    void light_hasExpectedMetadata() {
        assertThat(Theme.LIGHT.value())
            .as("unexpected value")
            .isEqualTo("light");
        assertThat(Theme.LIGHT.label())
            .as("unexpected label")
            .isEqualTo("Light");
        assertThat(Theme.LIGHT.title())
            .as("unexpected title")
            .isEqualTo("Light theme");
        assertThat(Theme.LIGHT.alt())
            .as("unexpected alt text")
            .isEqualTo("Dashboard in light mode");
        assertThat(Theme.LIGHT.previewImage())
            .as("unexpected preview image")
            .isEqualTo("page-nova-full-light");
    }

    @Test
    void dark_hasExpectedMetadata() {
        assertThat(Theme.DARK.value())
            .as("unexpected value")
            .isEqualTo("dark");
        assertThat(Theme.DARK.label())
            .as("unexpected label")
            .isEqualTo("Dark");
        assertThat(Theme.DARK.title())
            .as("unexpected title")
            .isEqualTo("Dark theme");
        assertThat(Theme.DARK.alt())
            .as("unexpected alt text")
            .isEqualTo("Dashboard in dark mode");
        assertThat(Theme.DARK.previewImage())
            .as("unexpected preview image")
            .isEqualTo("page-nova-full-dark");
    }

    // ── DEFAULT ─────────────────────────────────────────────────────────────

    @Test
    void default_isSystem() {
        assertThat(Theme.DEFAULT)
            .as("unexpected default theme")
            .isEqualTo(Theme.SYSTEM);
    }

    // ── from ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"system", "light", "dark"})
    void from_knownValue_returnsMatchingTheme(final String value) {
        assertThat(Theme.from(value).value())
            .as("expected the value to round-trip through the matching theme")
            .isEqualTo(value);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"midnight", "solarized", "", "System", "Dark", "none", "blue", " dark "})
    void from_unknownValue_returnsDefault(final String value) {
        assertThat(Theme.from(value))
            .as("expected an unrecognised value to default to the default theme")
            .isEqualTo(Theme.DEFAULT);
    }
}
