package dev.lifetracker.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
