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

    // ── Valid page sizes (any whole number in [1, 100] is accepted, presets and custom alike) ──

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 7, 9, 10, 11, 24, 25, 33, 49, 50, 99, 100})
    void isValidPageSize_inRange_returnsTrue(final int size) {
        assertThat(UserSettings.isValidPageSize(size))
            .as("expected value to be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101, 200, -1, -100, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void isValidPageSize_outOfRange_returnsFalse(final int size) {
        assertThat(UserSettings.isValidPageSize(size))
            .as("expected value to be rejected")
            .isFalse();
    }

    // ── parsePageSize: accepts an in-range whole number, rejects everything else with null ─────

    @ParameterizedTest
    @ValueSource(strings = {"1", "5", "7", "100", " 25 ", "050"})
    void parsePageSize_validValues_returnParsedInt(final String raw) {
        assertThat(UserSettings.parsePageSize(raw))
            .as("unexpected value")
            .isEqualTo(Integer.parseInt(raw.strip()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "999", "1.5", "abc", "", "   ", "5px", "0x10"})
    void parsePageSize_invalidValues_returnNull(final String raw) {
        assertThat(UserSettings.parsePageSize(raw))
            .as("expected an invalid page size to be rejected")
            .isNull();
    }

    @Test
    void parsePageSize_null_returnsNull() {
        assertThat(UserSettings.parsePageSize(null))
            .as("expected null input to be rejected")
            .isNull();
    }

    @Test
    void pageSizeRangeMessage_statesTheRange() {
        assertThat(UserSettings.PAGE_SIZE_RANGE_MESSAGE)
            .as("the rejection message should state the accepted range")
            .contains("1")
            .contains("100");
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test
    void defaultPageSize_isFive() {
        assertThat(UserSettings.DEFAULT_PAGE_SIZE)
            .as("unexpected value")
            .isEqualTo(5);
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

    // ── Valid decimal places (any whole number in [0, 5] is accepted, presets and custom alike) ──

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void isValidDecimalPlaces_inRange_returnsTrue(final int places) {
        assertThat(UserSettings.isValidDecimalPlaces(places))
            .as("expected value to be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 6, 10, 100, -100, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void isValidDecimalPlaces_outOfRange_returnsFalse(final int places) {
        assertThat(UserSettings.isValidDecimalPlaces(places))
            .as("expected value to be rejected")
            .isFalse();
    }

    // ── parseDecimalPlaces: accepts an in-range whole number, rejects everything else with null ──

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "3", "5", " 2 ", "04"})
    void parseDecimalPlaces_validValues_returnParsedInt(final String raw) {
        assertThat(UserSettings.parseDecimalPlaces(raw))
            .as("unexpected value")
            .isEqualTo(Integer.parseInt(raw.strip()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "6", "9", "100", "1.5", "abc", "", "   ", "2px", "0x10"})
    void parseDecimalPlaces_invalidValues_returnNull(final String raw) {
        assertThat(UserSettings.parseDecimalPlaces(raw))
            .as("expected an invalid decimal-place count to be rejected")
            .isNull();
    }

    @Test
    void parseDecimalPlaces_null_returnsNull() {
        assertThat(UserSettings.parseDecimalPlaces(null))
            .as("expected null input to be rejected")
            .isNull();
    }

    @Test
    void decimalPlacesRangeMessage_statesTheRange() {
        assertThat(UserSettings.DECIMAL_PLACES_RANGE_MESSAGE)
            .as("the rejection message should state the accepted range")
            .contains("0")
            .contains("5");
    }

    @Test
    void defaultDecimalPlaces_isOne() {
        assertThat(UserSettings.DEFAULT_DECIMAL_PLACES)
            .as("unexpected value")
            .isEqualTo(1);
    }

    @Test
    void decimalPlacesOptions_containsExactlyThreeValues() {
        assertThat(UserSettings.DECIMAL_PLACES_OPTIONS.size())
            .as("unexpected value")
            .isEqualTo(3);
    }

    @Test
    void decimalPlacesOptions_defaultIsIncluded() {
        assertThat(UserSettings.DECIMAL_PLACES_OPTIONS)
            .as("options must include the default decimal-place count")
            .contains(UserSettings.DEFAULT_DECIMAL_PLACES);
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
        assertThat(choices.getFirst().value())
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
        assertThat(selected.getFirst().value())
            .as("server default selected when user inherits")
            .isEqualTo("Pacific/Auckland");
        assertThat(selected.getFirst().label())
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
        assertThat(selected.getFirst().value())
            .as("user override selected")
            .isEqualTo("Asia/Tokyo");
        assertThat(selected.getFirst().label())
            .as("unexpected value")
            .isEqualTo("Asia/Tokyo (UTC+9)");
        // The server default is NOT selected when the user has an override.
        assertThat(choices.stream().filter(c -> "UTC".equals(c.value())).noneMatch(UserSettings.TimezoneChoice::selected))
            .as("server default not selected when user has override")
            .isTrue();
    }
}
