package dev.lifetracker.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserSettingsTest {

    // ── Valid page sizes (allowed through unchanged) ───────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {5, 10, 25, 50, 100})
    void sanitisePageSize_validValues_passedThrough(int size) {
        assertEquals(size, UserSettings.sanitisePageSize(size));
    }

    // ── Invalid page sizes (all fall back to default of 10) ──────────────────

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 9, 11, 24, 26, 49, 51, 99, 101, 200, -1, -100})
    void sanitisePageSize_invalidValues_returnsDefault(int size) {
        assertEquals(UserSettings.DEFAULT_PAGE_SIZE, UserSettings.sanitisePageSize(size));
    }

    @Test
    void sanitisePageSize_maxInt_returnsDefault() {
        assertEquals(UserSettings.DEFAULT_PAGE_SIZE, UserSettings.sanitisePageSize(Integer.MAX_VALUE));
    }

    @Test
    void sanitisePageSize_minInt_returnsDefault() {
        assertEquals(UserSettings.DEFAULT_PAGE_SIZE, UserSettings.sanitisePageSize(Integer.MIN_VALUE));
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    @Test
    void defaultPageSize_isTen() {
        assertEquals(10, UserSettings.DEFAULT_PAGE_SIZE);
    }

    @Test
    void pageSizeOptions_containsExactlyFiveValues() {
        assertEquals(5, UserSettings.PAGE_SIZE_OPTIONS.size());
    }

    @Test
    void pageSizeOptions_defaultIsIncluded() {
        assertTrue_contains();
    }

    private static void assertTrue_contains() {
        if (!UserSettings.PAGE_SIZE_OPTIONS.contains(UserSettings.DEFAULT_PAGE_SIZE)) {
            throw new AssertionError("Expected list to contain " + UserSettings.DEFAULT_PAGE_SIZE + " but was: " + UserSettings.PAGE_SIZE_OPTIONS);
        }
    }

    // ── Calendar view sanitisation ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"full", "minimal", "stacked"})
    void sanitiseCalendarView_validValues_passedThrough(String view) {
        assertEquals(view, UserSettings.sanitiseCalendarView(view));
    }

    @ParameterizedTest
    @ValueSource(strings = {"grid", "compact", "", "FULL", "Minimal", "none", "list"})
    void sanitiseCalendarView_invalidValues_returnsDefault(String view) {
        assertEquals(UserSettings.DEFAULT_CALENDAR_VIEW, UserSettings.sanitiseCalendarView(view));
    }

    @Test
    void defaultCalendarView_isFull() {
        assertEquals("full", UserSettings.DEFAULT_CALENDAR_VIEW);
    }

    @Test
    void calendarViewOptions_containsExactlyThreeValues() {
        assertEquals(3, UserSettings.CALENDAR_VIEW_OPTIONS.size());
    }

    @Test
    void calendarViewOptions_defaultIsIncluded() {
        assertTrue(UserSettings.CALENDAR_VIEW_OPTIONS.contains(UserSettings.DEFAULT_CALENDAR_VIEW));
    }

    // ── Timezone sanitisation ──────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Pacific/Auckland", "Europe/London", "America/New_York"})
    void sanitiseTimezone_offeredValues_passedThrough(String tz) {
        assertEquals(tz, UserSettings.sanitiseTimezone(tz));
    }

    @ParameterizedTest
    // Blank, unknown, mis-cased, or valid-but-not-offered zones all collapse to "use server default".
    @ValueSource(strings = {"", " ", "utc", "Mars/Phobos", "Asia/Atlantis", "Europe/Atlantis", "GMT+5"})
    void sanitiseTimezone_invalidOrUnoffered_returnsNull(String tz) {
        assertNull(UserSettings.sanitiseTimezone(tz));
    }

    @Test
    void sanitiseTimezone_null_returnsNull() {
        assertNull(UserSettings.sanitiseTimezone(null));
    }

    @Test
    void timezoneOptions_allValidZoneIds() {
        for (String tz : UserSettings.TIMEZONE_OPTIONS) {
            // Throws DateTimeException if any offered id is not a real zone.
            java.time.ZoneId.of(tz);
        }
    }

    // ── UTC offset labels ───────────────────────────────────────────────────────

    @Test
    void utcOffsetLabel_formatsWholeAndHalfHourOffsets() {
        assertEquals("UTC", UserSettings.utcOffsetLabel(java.time.ZoneOffset.UTC));
        assertEquals("UTC+12", UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHours(12)));
        assertEquals("UTC-8", UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHours(-8)));
        assertEquals("UTC+5:30", UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHoursMinutes(5, 30)));
        assertEquals("UTC-3:30", UserSettings.utcOffsetLabel(java.time.ZoneOffset.ofHoursMinutes(-3, -30)));
    }

    // ── Timezone picker choices ─────────────────────────────────────────────────

    // 15 Jun 2026 noon UTC: a stable, DST-deterministic instant for offset assertions
    // (NZ standard time UTC+12, US daylight time).
    private static final java.time.Instant NOW =
            java.time.LocalDate.of(2026, 6, 15).atTime(12, 0).toInstant(java.time.ZoneOffset.UTC);

    @Test
    void timezoneChoices_defaultEntryIsFirstWithLabelAndInheritValue() {
        var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("Pacific/Auckland"), NOW, null);

        var first = choices.get(0);
        assertEquals("", first.value());
        assertEquals("Pacific/Auckland (UTC+12)", first.label());
        assertTrue(first.selected(), "default entry selected when user inherits (null timezone)");
    }

    @Test
    void timezoneChoices_utcLabelHasNoRedundantOffset() {
        // As the server default, UTC renders as plain "UTC", not "UTC (UTC)".
        var asDefault = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, null);
        assertEquals("UTC", asDefault.get(0).label());

        // As a body entry (non-UTC server default), it is still just "UTC".
        var asBody = UserSettings.timezoneChoices(java.time.ZoneId.of("Pacific/Auckland"), NOW, null);
        var utc = asBody.stream().filter(c -> "UTC".equals(c.value())).findFirst().orElseThrow();
        assertEquals("UTC", utc.label());
    }

    @Test
    void timezoneChoices_serverDefaultNotDuplicatedInBody() {
        var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("Pacific/Auckland"), NOW, null);

        // Pacific/Auckland appears only as the [default] entry, never as a plain body option.
        long aucklandBodyEntries = choices.stream()
                .filter(c -> "Pacific/Auckland".equals(c.value()))
                .count();
        assertEquals(0, (int) aucklandBodyEntries);
        // Body holds the remaining curated zones (all options minus the default).
        assertEquals(UserSettings.TIMEZONE_OPTIONS.size(), choices.size());
    }

    @Test
    void timezoneChoices_bodySortedByOffsetAscending() {
        var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, null);

        // Skip the default entry; the rest must be ordered most-behind-UTC → most-ahead.
        int prev = Integer.MIN_VALUE;
        for (int i = 1; i < choices.size(); i++) {
            int offset = java.time.ZoneId.of(choices.get(i).value())
                    .getRules().getOffset(NOW).getTotalSeconds();
            assertTrue(offset >= prev, "choices must be sorted by ascending UTC offset");
            prev = offset;
        }
        // Most-behind option (America/Los_Angeles, PDT UTC-7 in June) sorts ahead of UTC zones.
        assertEquals("America/Los_Angeles", choices.get(1).value());
    }

    @Test
    void timezoneChoices_userOverrideMarksMatchingBodyEntrySelected() {
        var choices = UserSettings.timezoneChoices(java.time.ZoneId.of("UTC"), NOW, "Asia/Tokyo");

        assertTrue(choices.stream().noneMatch(c -> c.value().isEmpty() && c.selected()),
                "default entry not selected when the user has an override");
        var tokyo = choices.stream().filter(c -> "Asia/Tokyo".equals(c.value())).findFirst().orElseThrow();
        assertTrue(tokyo.selected());
        assertEquals("Asia/Tokyo (UTC+9)", tokyo.label());
    }
}
