package dev.lifetracker.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserSettingsTest {

    // ── Valid page sizes (allowed through unchanged) ───────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {10, 25, 50, 100})
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
    void pageSizeOptions_containsExactlyFourValues() {
        assertEquals(4, UserSettings.PAGE_SIZE_OPTIONS.size());
    }

    @Test
    void pageSizeOptions_defaultIsIncluded() {
        assertTrue_contains(UserSettings.PAGE_SIZE_OPTIONS, UserSettings.DEFAULT_PAGE_SIZE);
    }

    private static void assertTrue_contains(java.util.List<Integer> list, int value) {
        if (!list.contains(value)) {
            throw new AssertionError("Expected list to contain " + value + " but was: " + list);
        }
    }
}
