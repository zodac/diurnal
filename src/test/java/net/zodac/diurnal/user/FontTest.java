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

class FontTest {

    // ── Constant metadata ───────────────────────────────────────────────────

    @Test
    void nova_hasExpectedMetadata() {
        assertThat(Font.NOVA.value())
            .as("unexpected value")
            .isEqualTo("nova");
        assertThat(Font.NOVA.label())
            .as("unexpected label")
            .isEqualTo("Nova");
        assertThat(Font.NOVA.title())
            .as("unexpected title")
            .isEqualTo("Nova font");
        assertThat(Font.NOVA.alt())
            .as("unexpected alt text")
            .isEqualTo("Dashboard shown in the Nova font");
        assertThat(Font.NOVA.previewImage())
            .as("unexpected preview image")
            .isEqualTo("page-nova-full-dark");
    }

    @Test
    void standard_hasExpectedMetadata() {
        assertThat(Font.STANDARD.value())
            .as("unexpected value")
            .isEqualTo("standard");
        assertThat(Font.STANDARD.label())
            .as("unexpected label")
            .isEqualTo("Standard");
        assertThat(Font.STANDARD.title())
            .as("unexpected title")
            .isEqualTo("Standard font");
        assertThat(Font.STANDARD.alt())
            .as("unexpected alt text")
            .isEqualTo("Dashboard shown in the standard system font");
        assertThat(Font.STANDARD.previewImage())
            .as("unexpected preview image")
            .isEqualTo("page-standard-full-dark");
    }

    @Test
    void dyslexic_hasExpectedMetadata() {
        assertThat(Font.DYSLEXIC.value())
            .as("unexpected value")
            .isEqualTo("dyslexic");
        assertThat(Font.DYSLEXIC.label())
            .as("unexpected label")
            .isEqualTo("OpenDyslexic");
        assertThat(Font.DYSLEXIC.title())
            .as("unexpected title")
            .isEqualTo("OpenDyslexic font");
        assertThat(Font.DYSLEXIC.alt())
            .as("unexpected alt text")
            .isEqualTo("Dashboard shown in the OpenDyslexic accessibility font");
        assertThat(Font.DYSLEXIC.previewImage())
            .as("unexpected preview image")
            .isEqualTo("page-dyslexic-full-dark");
    }

    // ── DEFAULT ─────────────────────────────────────────────────────────────

    @Test
    void default_isNova() {
        assertThat(Font.DEFAULT)
            .as("unexpected default font")
            .isEqualTo(Font.NOVA);
    }

    // ── from ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"nova", "standard", "dyslexic"})
    void from_knownValue_returnsMatchingFont(final String value) {
        assertThat(Font.from(value).value())
            .as("expected the value to round-trip through the matching font")
            .isEqualTo(value);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"serif", "system", "", "Nova", "Standard", "none", "mono", " nova "})
    void from_unknownValue_returnsDefault(final String value) {
        assertThat(Font.from(value))
            .as("expected an unrecognised value to default to the default font")
            .isEqualTo(Font.DEFAULT);
    }
}
