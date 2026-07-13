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

class CalendarViewTest {

    // ── Constant metadata ───────────────────────────────────────────────────

    @Test
    void full_hasExpectedMetadata() {
        assertThat(CalendarView.FULL.value())
            .as("unexpected value")
            .isEqualTo("full");
        assertThat(CalendarView.FULL.label())
            .as("unexpected label")
            .isEqualTo("Full");
        assertThat(CalendarView.FULL.title())
            .as("unexpected title")
            .isEqualTo("Full calendar");
        assertThat(CalendarView.FULL.alt())
            .as("unexpected alt text")
            .isEqualTo("Dashboard calendar showing events as text");
        assertThat(CalendarView.FULL.previewImage())
            .as("unexpected preview image")
            .isEqualTo("cal-nova-full-dark");
    }

    @Test
    void minimal_hasExpectedMetadata() {
        assertThat(CalendarView.MINIMAL.value())
            .as("unexpected value")
            .isEqualTo("minimal");
        assertThat(CalendarView.MINIMAL.label())
            .as("unexpected label")
            .isEqualTo("Minimal");
        assertThat(CalendarView.MINIMAL.title())
            .as("unexpected title")
            .isEqualTo("Minimal calendar");
        assertThat(CalendarView.MINIMAL.alt())
            .as("unexpected alt text")
            .isEqualTo("Dashboard calendar showing a coloured dot per action");
        assertThat(CalendarView.MINIMAL.previewImage())
            .as("unexpected preview image")
            .isEqualTo("cal-nova-minimal-dark");
    }

    @Test
    void stacked_hasExpectedMetadata() {
        assertThat(CalendarView.STACKED.value())
            .as("unexpected value")
            .isEqualTo("stacked");
        assertThat(CalendarView.STACKED.label())
            .as("unexpected label")
            .isEqualTo("Stacked");
        assertThat(CalendarView.STACKED.title())
            .as("unexpected title")
            .isEqualTo("Stacked calendar");
        assertThat(CalendarView.STACKED.alt())
            .as("unexpected alt text")
            .isEqualTo("Dashboard calendar showing horizontal bars per action");
        assertThat(CalendarView.STACKED.previewImage())
            .as("unexpected preview image")
            .isEqualTo("cal-nova-stacked-dark");
    }

    // ── DEFAULT ─────────────────────────────────────────────────────────────

    @Test
    void default_isFull() {
        assertThat(CalendarView.DEFAULT)
            .as("unexpected default calendar view")
            .isEqualTo(CalendarView.FULL);
    }

    // ── from ────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"full", "minimal", "stacked"})
    void from_knownValue_returnsMatchingView(final String value) {
        assertThat(CalendarView.from(value).value())
            .as("expected the value to round-trip through the matching calendar view")
            .isEqualTo(value);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"grid", "compact", "", "FULL", "Minimal", "none", "list", " full "})
    void from_unknownValue_returnsDefault(final String value) {
        assertThat(CalendarView.from(value))
            .as("expected an unrecognised value to default to the default calendar view")
            .isEqualTo(CalendarView.DEFAULT);
    }
}
