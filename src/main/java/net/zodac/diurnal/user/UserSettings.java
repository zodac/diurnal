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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A user's display preferences plus the allow-lists and sanitisers that validate them.
 */
public record UserSettings(String theme, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final String DEFAULT_THEME = "system";

    // Presets offered in the picker; a user may also enter any value in [MIN_PAGE_SIZE, MAX_PAGE_SIZE].
    public static final List<Integer> PAGE_SIZE_OPTIONS = List.of(5, 10, 25, 50, 100);
    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 100;
    // User-facing rejection message when an out-of-range or non-numeric page size is submitted.
    public static final String PAGE_SIZE_RANGE_MESSAGE =
        "Items per page must be a whole number between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE + ".";
    public static final List<String> THEME_OPTIONS = List.of("system", "light", "dark");

    // Whether the dashboard renders the per-action stats-summary strip.
    public static final boolean DEFAULT_SHOW_STATS_SUMMARY = true;

    // Number of decimal places used to render fractional stats (e.g. the weekly average).
    public static final int DEFAULT_DECIMAL_PLACES = 1;
    public static final int MIN_DECIMAL_PLACES = 0;
    public static final int MAX_DECIMAL_PLACES = 5;
    // Presets offered in the picker; a user may also enter any value in
    // [MIN_DECIMAL_PLACES, MAX_DECIMAL_PLACES].
    public static final List<Integer> DECIMAL_PLACES_OPTIONS = List.of(0, 1, 2);
    // User-facing rejection message when an out-of-range or non-numeric decimal-place count is submitted.
    public static final String DECIMAL_PLACES_RANGE_MESSAGE =
        "Decimal places must be a whole number between " + MIN_DECIMAL_PLACES + " and " + MAX_DECIMAL_PLACES + ".";

    public static final String DEFAULT_CALENDAR_VIEW = "full";
    public static final List<String> CALENDAR_VIEW_OPTIONS = List.of("full", "minimal", "stacked");

    public static final String DEFAULT_FONT = "nova";
    public static final List<String> FONT_OPTIONS = List.of("nova", "standard");

    // Curated list of common IANA zones offered in Settings. A user whose timezone is null
    // (not one of these) falls back to the server default (app.timezone). The picker orders every
    // zone by its current UTC offset (see timezoneChoices), not by this declaration order.
    public static final List<String> TIMEZONE_OPTIONS = List.of(
        "UTC",
        "Pacific/Auckland",
        "Australia/Sydney",
        "Asia/Tokyo",
        "Asia/Shanghai",
        "Asia/Kolkata",
        "Asia/Dubai",
        "Europe/Berlin",
        "Europe/Paris",
        "Europe/London",
        "America/Sao_Paulo",
        "America/New_York",
        "America/Chicago",
        "America/Denver",
        "America/Los_Angeles");

    /**
     * Extracts the display preferences from a {@link User} entity.
     */
    public static UserSettings from(final User user) {
        return new UserSettings(user.theme, user.pageSize);
    }

    /**
     * Whether the given page size is within the accepted range ({@link #MIN_PAGE_SIZE}–{@link #MAX_PAGE_SIZE}).
     */
    public static boolean isValidPageSize(final int value) {
        return value >= MIN_PAGE_SIZE && value <= MAX_PAGE_SIZE;
    }

    /**
     * Parses a submitted page size, returning the value only if it is a whole number within the accepted range
     * ({@link #MIN_PAGE_SIZE}–{@link #MAX_PAGE_SIZE}), else {@code null}. Unlike the other preferences, an invalid page size is rejected (not coerced
     * to a default) so the caller can surface an error and retain the user's previous value.
     *
     * @param raw the raw submitted value (may be {@code null}, blank, non-numeric or out of range)
     * @return the valid page size, or {@code null} if the input is not acceptable
     */
    public static @Nullable Integer parsePageSize(@Nullable final String raw) {
        if (raw == null) {
            return null;
        }
        try {
            final int value = Integer.parseInt(raw.strip());
            return isValidPageSize(value) ? value : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether the given decimal-place count is within the accepted range ({@link #MIN_DECIMAL_PLACES}–{@link #MAX_DECIMAL_PLACES}).
     */
    public static boolean isValidDecimalPlaces(final int value) {
        return value >= MIN_DECIMAL_PLACES && value <= MAX_DECIMAL_PLACES;
    }

    /**
     * Parses a submitted decimal-place count, returning the value only if it is a whole number within the accepted range
     * ({@link #MIN_DECIMAL_PLACES}–{@link #MAX_DECIMAL_PLACES}), else {@code null}. Like {@link #parsePageSize(String)}, an invalid value is rejected
     * (not coerced to a default) so the caller can surface an error and retain the user's previous value.
     *
     * @param raw the raw submitted value (may be {@code null}, blank, non-numeric or out of range)
     * @return the valid decimal-place count, or {@code null} if the input is not acceptable
     */
    public static @Nullable Integer parseDecimalPlaces(@Nullable final String raw) {
        if (raw == null) {
            return null;
        }
        try {
            final int value = Integer.parseInt(raw.strip());
            return isValidDecimalPlaces(value) ? value : null;
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns the requested theme if it is an allowed option, else the default.
     */
    public static String sanitiseTheme(@Nullable final String requested) {
        // The allow-lists are List.of(...) (immutable), whose contains(null) throws NPE — so guard
        // null explicitly. null (an absent form param on a per-setting PATCH) → the default.
        return requested != null && THEME_OPTIONS.contains(requested) ? requested : DEFAULT_THEME;
    }

    /**
     * Returns the requested calendar view if it is an allowed option, else the default.
     */
    public static String sanitiseCalendarView(@Nullable final String requested) {
        return requested != null && CALENDAR_VIEW_OPTIONS.contains(requested) ? requested : DEFAULT_CALENDAR_VIEW;
    }

    /**
     * Returns the requested font if it is an allowed option, else the default.
     */
    public static String sanitiseFont(@Nullable final String requested) {
        return requested != null && FONT_OPTIONS.contains(requested) ? requested : DEFAULT_FONT;
    }

    /**
     * Returns the requested zone if it is one of the offered options, else null ("use server default").
     */
    public static @Nullable String sanitiseTimezone(@Nullable final String requested) {
        return requested != null && TIMEZONE_OPTIONS.contains(requested) ? requested : null;
    }

    /**
     * A single option in the timezone picker: form {@code value}, display {@code label}, pre-selected flag.
     */
    public record TimezoneChoice(String value, String label, boolean selected) {
    }

    /**
     * Builds the timezone picker options, ordered by their current UTC offset (most behind → most ahead) and evaluated at {@code now} (so the offsets
     * reflect the current DST state). Every curated zone is offered with its own id as the form value. The option matching the user's stored timezone
     * is pre-selected; when the user has no override (null), the server default zone is selected instead, so a new user's initial value mirrors the
     * server default.
     *
     * @param serverZone the server default zone, used as the initial selection when the user has no override
     * @param now the instant at which UTC offsets are evaluated
     * @param selectedTimezone the user's stored timezone (null = inheriting the server default)
     */
    public static List<TimezoneChoice> timezoneChoices(final ZoneId serverZone, final Instant now, @Nullable final String selectedTimezone) {
        final String effectiveZone = selectedTimezone == null ? serverZone.getId() : selectedTimezone;
        return TIMEZONE_OPTIONS.stream()
                .map(ZoneId::of)
                .sorted(Comparator
                        .comparingInt((ZoneId zone) -> zone.getRules().getOffset(now).getTotalSeconds())
                        .thenComparing(ZoneId::getId))
                .map(zone -> new TimezoneChoice(zone.getId(), zoneLabel(zone, now), zone.getId().equals(effectiveZone)))
                .toList();
    }

    private static String zoneLabel(final ZoneId zone, final Instant now) {
        final String id = zone.getId();
        final String offset = utcOffsetLabel(zone.getRules().getOffset(now));
        // The UTC zone's id already is its offset label — don't render the redundant "UTC (UTC)".
        return id.equals(offset) ? id : id + " (" + offset + ")";
    }

    /**
     * Formats an offset as {@code "UTC"}, {@code "UTC+12"}, {@code "UTC-8"}, or {@code "UTC+5:30"}.
     */
    static String utcOffsetLabel(final ZoneOffset offset) {
        final int totalSeconds = offset.getTotalSeconds();
        final int absSeconds = Math.abs(totalSeconds);
        final int hours = absSeconds / 3600;
        final int minutes = (absSeconds % 3600) / 60;
        final String body = minutes == 0 ? String.valueOf(hours) : hours + ":" + String.format("%02d", minutes);
        // Zero falls through to the plain "UTC" label; keeping the sign checks reachable for 0 means
        // the boundary (> 0 / < 0) is testable rather than an equivalent mutant.
        if (totalSeconds > 0) {
            return "UTC+" + body;
        }
        if (totalSeconds < 0) {
            return "UTC-" + body;
        }
        return "UTC";
    }
}
