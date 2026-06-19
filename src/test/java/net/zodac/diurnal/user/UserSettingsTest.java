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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(size, UserSettings.sanitisePageSize(size), "unexpected value");
    }

    // ── Invalid page sizes (all fall back to default of 10) ──────────────────

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 9, 11, 24, 26, 49, 51, 99, 101, 200, -1, -100})
    void sanitisePageSize_invalidValues_returnsDefault(final int size) {
        assertEquals(UserSettings.DEFAULT_PAGE_SIZE, UserSettings.sanitisePageSize(size), "unexpected value");
    }

    @Test
    void sanitisePageSize_maxInt_returnsDefault() {
        assertEquals(UserSettings.DEFAULT_PAGE_SIZE, UserSettings.sanitisePageSize(Integer.MAX_VALUE), "unexpected value");
    }

    @Test
    void sanitisePageSize_minInt_returnsDefault() {
        assertEquals(UserSettings.DEFAULT_PAGE_SIZE, UserSettings.sanitisePageSize(Integer.MIN_VALUE), "unexpected value");
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test
    void defaultPageSize_isTen() {
        assertEquals(10, UserSettings.DEFAULT_PAGE_SIZE, "unexpected value");
    }

    @Test
    void pageSizeOptions_containsExactlyFiveValues() {
        assertEquals(5, UserSettings.PAGE_SIZE_OPTIONS.size(), "unexpected value");
    }

    @Test
    void pageSizeOptions_defaultIsIncluded() {
        assertDefaultPageSizeIncluded();
    }

    private static void assertDefaultPageSizeIncluded() {
        if (!UserSettings.PAGE_SIZE_OPTIONS.contains(UserSettings.DEFAULT_PAGE_SIZE)) {
            throw new AssertionError("Expected list to contain " + UserSettings.DEFAULT_PAGE_SIZE + " but was: " + UserSettings.PAGE_SIZE_OPTIONS);
        }
    }

    // ── Calendar view sanitisation ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"full", "minimal", "stacked"})
    void sanitiseCalendarView_validValues_passedThrough(final String view) {
        assertEquals(view, UserSettings.sanitiseCalendarView(view), "unexpected value");
    }

    @ParameterizedTest
    @ValueSource(strings = {"grid", "compact", "", "FULL", "Minimal", "none", "list"})
    void sanitiseCalendarView_invalidValues_returnsDefault(final String view) {
        assertEquals(UserSettings.DEFAULT_CALENDAR_VIEW, UserSettings.sanitiseCalendarView(view), "unexpected value");
    }

    @Test
    void defaultCalendarView_isFull() {
        assertEquals("full", UserSettings.DEFAULT_CALENDAR_VIEW, "unexpected value");
    }

    @Test
    void calendarViewOptions_containsExactlyThreeValues() {
        assertEquals(3, UserSettings.CALENDAR_VIEW_OPTIONS.size(), "unexpected value");
    }

    @Test
    void calendarViewOptions_defaultIsIncluded() {
        assertTrue(UserSettings.CALENDAR_VIEW_OPTIONS.contains(UserSettings.DEFAULT_CALENDAR_VIEW), "expected condition to be true");
    }

    // ── Timezone sanitisation ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Auckland", "Europe/London", "America/New_York"})
    void sanitiseTimezone_offeredValues_passedThrough(final String tz) {
        assertEquals(tz, UserSettings.sanitiseTimezone(tz), "unexpected value");
    }

    @ParameterizedTest
    // Blank, unknown, mis-cased, or valid-but-not-offered zones all collapse to "use server default".
    @ValueSource(strings = {"", " ", "utc", "Mars/Phobos", "Asia/Atlantis", "Europe/Atlantis", "GMT+5"})
    void sanitiseTimezone_invalidOrUnoffered_returnsNull(final String tz) {
        assertNull(UserSettings.sanitiseTimezone(tz), "expected null");
    }

    @Test
    void sanitiseTimezone_null_returnsNull() {
        assertNull(UserSettings.sanitiseTimezone(null), "expected null");
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
        assertEquals("UTC", UserSettings.utcOffsetLabel(java.time.ZoneOffset.UTC), "unexpected value");
        assertEquals("UTC+12", UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHours(12)), "unexpected value");
        assertEquals("UTC-8", UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHours(-8)), "unexpected value");
        assertEquals("UTC+5:30", UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHoursMinutes(5, 30)), "unexpected value");
        assertEquals("UTC-3:30", UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHoursMinutes(-3, -30)), "unexpected value");
    }

    // ── Timezone picker choices ─────────────────────────────────────────────────

    @Test
    void timezoneChoices_offersEveryCuratedZoneWithItsOwnIdAsValue() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, null);

        assertEquals(UserSettings.TIMEZONE_OPTIONS.size(), choices.size(), "unexpected value");
        // No "inherit" sentinel entry: every option's value is a real zone id from the curated list.
        assertTrue(choices.stream().noneMatch(c -> c.value().isEmpty()), "no empty-value entry expected");
        assertTrue(choices.stream().allMatch(c -> UserSettings.TIMEZONE_OPTIONS.contains(c.value())),
                "every choice value must be a curated zone id");
    }

    @Test
    void timezoneChoices_sortedByUtcOffsetAscending() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, null);

        int prev = Integer.MIN_VALUE;
        for (final var choice : choices) {
            final int offset = java.time.ZoneId.of(choice.value()).getRules().getOffset(NOW).getTotalSeconds();
            assertTrue(offset >= prev, "choices must be sorted by ascending UTC offset");
            prev = offset;
        }
        // Most-behind option (America/Los_Angeles, PDT UTC-7 in June) sorts first.
        assertEquals("America/Los_Angeles", choices.get(0).value(), "unexpected value");
    }

    @Test
    void timezoneChoices_utcLabelHasNoRedundantOffset() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, null);

        final var utc = choices.stream().filter(c -> "UTC".equals(c.value())).findFirst().orElseThrow();
        assertEquals("UTC", utc.label(), "unexpected value");
    }

    @Test
    void timezoneChoices_serverDefaultSelectedWhenUserInherits() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("Pacific/Auckland"), NOW, null);

        final var selected = choices.stream().filter(UserSettings.TimezoneChoice::selected).toList();
        assertEquals(1, selected.size(), "exactly one option selected");
        assertEquals("Pacific/Auckland", selected.get(0).value(), "server default selected when user inherits");
        assertEquals("Pacific/Auckland (UTC+12)", selected.get(0).label(), "unexpected value");
    }

    @Test
    void timezoneChoices_userOverrideMarksMatchingEntrySelected() {
        final var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, "Asia/Tokyo");

        final var selected = choices.stream().filter(UserSettings.TimezoneChoice::selected).toList();
        assertEquals(1, selected.size(), "exactly one option selected");
        assertEquals("Asia/Tokyo", selected.get(0).value(), "user override selected");
        assertEquals("Asia/Tokyo (UTC+9)", selected.get(0).label(), "unexpected value");
        // The server default is NOT selected when the user has an override.
        assertTrue(choices.stream().filter(c -> "UTC".equals(c.value())).noneMatch(UserSettings.TimezoneChoice::selected),
                "server default not selected when user has override");
    }
}
