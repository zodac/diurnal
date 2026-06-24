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
import org.junit.jupiter.params.provider.ValueSource;

class UserSettingsTest {

    // 15 Jun 2026 noon UTC: a stable, DST-deterministic instant for offset assertions
    // (NZ standard time UTC+12, US daylight time).
    private static final java.time.Instant NOW =
            java.time.LocalDate.of(2026, 6, 15).atTime(12, 0).toInstant(java.time.ZoneOffset.UTC);

    // ── Valid page sizes (allowed through unchanged) ───────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {5, 10, 25, 50, 100})
    void sanitisePageSize_validValues_passedThrough(final int size) {
        assertThat(UserSettings.sanitisePageSize(size))
            .as("unexpected value")
            .isEqualTo(size);
    }

    // ── Invalid page sizes (all fall back to default of 10) ──────────────────

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 9, 11, 24, 26, 49, 51, 99, 101, 200, -1, -100})
    void sanitisePageSize_invalidValues_returnsDefault(final int size) {
        assertThat(UserSettings.sanitisePageSize(size))
            .as("unexpected value")
            .isEqualTo(UserSettings.DEFAULT_PAGE_SIZE);
    }

    @Test
    void sanitisePageSize_maxInt_returnsDefault() {
        assertThat(UserSettings.sanitisePageSize(Integer.MAX_VALUE))
            .as("unexpected value")
            .isEqualTo(UserSettings.DEFAULT_PAGE_SIZE);
    }

    @Test
    void sanitisePageSize_minInt_returnsDefault() {
        assertThat(UserSettings.sanitisePageSize(Integer.MIN_VALUE))
            .as("unexpected value")
            .isEqualTo(UserSettings.DEFAULT_PAGE_SIZE);
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test
    void defaultPageSize_isTen() {
        assertThat(UserSettings.DEFAULT_PAGE_SIZE)
            .as("unexpected value")
            .isEqualTo(10);
    }

    @Test
    void pageSizeOptions_containsExactlyFiveValues() {
        assertThat(UserSettings.PAGE_SIZE_OPTIONS.size())
            .as("unexpected value")
            .isEqualTo(5);
    }

    @Test
    void pageSizeOptions_defaultIsIncluded() {
        assertThat(UserSettings.PAGE_SIZE_OPTIONS)
            .as("options must include the default page size")
            .contains(UserSettings.DEFAULT_PAGE_SIZE);
    }

    // ── Calendar view sanitisation ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"full", "minimal", "stacked"})
    void sanitiseCalendarView_validValues_passedThrough(final String view) {
        assertThat(UserSettings.sanitiseCalendarView(view))
            .as("unexpected value")
            .isEqualTo(view);
    }

    @ParameterizedTest
    @ValueSource(strings = {"grid", "compact", "", "FULL", "Minimal", "none", "list"})
    void sanitiseCalendarView_invalidValues_returnsDefault(final String view) {
        assertThat(UserSettings.sanitiseCalendarView(view))
            .as("unexpected value")
            .isEqualTo(UserSettings.DEFAULT_CALENDAR_VIEW);
    }

    @Test
    void defaultCalendarView_isFull() {
        assertThat(UserSettings.DEFAULT_CALENDAR_VIEW)
            .as("unexpected value")
            .isEqualTo("full");
    }

    @Test
    void calendarViewOptions_containsExactlyThreeValues() {
        assertThat(UserSettings.CALENDAR_VIEW_OPTIONS.size())
            .as("unexpected value")
            .isEqualTo(3);
    }

    @Test
    void calendarViewOptions_defaultIsIncluded() {
        assertThat(UserSettings.CALENDAR_VIEW_OPTIONS.contains(UserSettings.DEFAULT_CALENDAR_VIEW))
            .as("expected condition to be true")
            .isTrue();
    }

    // ── Font sanitisation ──────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"nova", "standard"})
    void sanitiseFont_validValues_passedThrough(final String font) {
        assertThat(UserSettings.sanitiseFont(font))
            .as("unexpected value")
            .isEqualTo(font);
    }

    @ParameterizedTest
    @ValueSource(strings = {"serif", "system", "", "Nova", "Standard", "none", "mono"})
    void sanitiseFont_invalidValues_returnsDefault(final String font) {
        assertThat(UserSettings.sanitiseFont(font))
            .as("unexpected value")
            .isEqualTo(UserSettings.DEFAULT_FONT);
    }

    @Test
    void defaultFont_isNova() {
        assertThat(UserSettings.DEFAULT_FONT)
            .as("unexpected value")
            .isEqualTo("nova");
    }

    @Test
    void fontOptions_containsExactlyTwoValues() {
        assertThat(UserSettings.FONT_OPTIONS.size())
            .as("unexpected value")
            .isEqualTo(2);
    }

    @Test
    void fontOptions_defaultIsIncluded() {
        assertThat(UserSettings.FONT_OPTIONS.contains(UserSettings.DEFAULT_FONT))
            .as("expected condition to be true")
            .isTrue();
    }

    // ── Timezone sanitisation ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Auckland", "Europe/London", "America/New_York"})
    void sanitiseTimezone_offeredValues_passedThrough(final String tz) {
        assertThat(UserSettings.sanitiseTimezone(tz))
            .as("unexpected value")
            .isEqualTo(tz);
    }

    @ParameterizedTest
    // Blank, unknown, mis-cased, or valid-but-not-offered zones all collapse to "use server default".
    @ValueSource(strings = {"", " ", "utc", "Mars/Phobos", "Asia/Atlantis", "Europe/Atlantis", "GMT+5"})
    void sanitiseTimezone_invalidOrUnoffered_returnsNull(final String tz) {
        assertThat(UserSettings.sanitiseTimezone(tz))
            .as("expected null")
            .isNull();
    }

    @Test
    void sanitiseTimezone_null_returnsNull() {
        assertThat(UserSettings.sanitiseTimezone(null))
            .as("expected null")
            .isNull();
    }

    @Test
    void timezoneOptions_allValidZoneIds() {
        for (final String tz : UserSettings.TIMEZONE_OPTIONS) {
            // Throws DateTimeException if any offered id is not a real zone.
            java.time.ZoneId.of(tz);
        }
    }

    // ── UTC offset labels ───────────────────────────────────────────────────────

    @Test
    void utcOffsetLabel_formatsWholeAndHalfHourOffsets() {
        assertThat(UserSettings.utcOffsetLabel(java.time.ZoneOffset.UTC))
            .as("unexpected value")
            .isEqualTo("UTC");
        assertThat(UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHours(12)))
            .as("unexpected value")
            .isEqualTo("UTC+12");
        assertThat(UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHours(-8)))
            .as("unexpected value")
            .isEqualTo("UTC-8");
        assertThat(UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHoursMinutes(5, 30)))
            .as("unexpected value")
            .isEqualTo("UTC+5:30");
        assertThat(UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHoursMinutes(-3, -30)))
            .as("unexpected value")
            .isEqualTo("UTC-3:30");
    }

    // ── Timezone picker choices ─────────────────────────────────────────────────

    @Test
    void timezoneChoices_offersEveryCuratedZoneWithItsOwnIdAsValue() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, null);

        assertThat(choices.size())
            .as("unexpected value")
            .isEqualTo(UserSettings.TIMEZONE_OPTIONS.size());
        // No "inherit" sentinel entry: every option's value is a real zone id from the curated list.
        assertThat(choices.stream().noneMatch(c -> c.value().isEmpty()))
            .as("no empty-value entry expected")
            .isTrue();
        assertThat(choices)
            .as("every choice value must be a curated zone id")
            .allMatch(c -> UserSettings.TIMEZONE_OPTIONS.contains(c.value()));
    }

    @Test
    void timezoneChoices_sortedByUtcOffsetAscending() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, null);

        int prev = Integer.MIN_VALUE;
        for (final var choice : choices) {
            final int offset = java.time.ZoneId.of(choice.value()).getRules().getOffset(NOW).getTotalSeconds();
            assertThat(offset >= prev)
                .as("choices must be sorted by ascending UTC offset")
                .isTrue();
            prev = offset;
        }
        // Most-behind option (America/Los_Angeles, PDT UTC-7 in June) sorts first.
        assertThat(choices.get(0).value())
            .as("unexpected value")
            .isEqualTo("America/Los_Angeles");
    }

    @Test
    void timezoneChoices_utcLabelHasNoRedundantOffset() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, null);

        final var utc = choices.stream().filter(c -> "UTC".equals(c.value())).findFirst().orElseThrow();
        assertThat(utc.label())
            .as("unexpected value")
            .isEqualTo("UTC");
    }

    @Test
    void timezoneChoices_serverDefaultSelectedWhenUserInherits() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("Pacific/Auckland"), NOW, null);

        final var selected = choices.stream().filter(UserSettings.TimezoneChoice::selected).toList();
        assertThat(selected.size())
            .as("exactly one option selected")
            .isEqualTo(1);
        assertThat(selected.get(0).value())
            .as("server default selected when user inherits")
            .isEqualTo("Pacific/Auckland");
        assertThat(selected.get(0).label())
            .as("unexpected value")
            .isEqualTo("Pacific/Auckland (UTC+12)");
    }

    @Test
    void timezoneChoices_userOverrideMarksMatchingEntrySelected() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, "Asia/Tokyo");

        final var selected = choices.stream().filter(UserSettings.TimezoneChoice::selected).toList();
        assertThat(selected.size())
            .as("exactly one option selected")
            .isEqualTo(1);
        assertThat(selected.get(0).value())
            .as("user override selected")
            .isEqualTo("Asia/Tokyo");
        assertThat(selected.get(0).label())
            .as("unexpected value")
            .isEqualTo("Asia/Tokyo (UTC+9)");
        // The server default is NOT selected when the user has an override.
        assertThat(choices.stream().filter(c -> "UTC".equals(c.value())).noneMatch(UserSettings.TimezoneChoice::selected))
            .as("server default not selected when user has override")
            .isTrue();
    }
}
