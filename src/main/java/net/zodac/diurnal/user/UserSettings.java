package net.zodac.diurnal.user;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record UserSettings(String theme, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final String DEFAULT_THEME = "system";

    public static final List<Integer> PAGE_SIZE_OPTIONS = List.of(5, 10, 25, 50, 100);
    public static final List<String> THEME_OPTIONS = List.of("system", "light", "dark");

    public static final String DEFAULT_CALENDAR_VIEW = "full";
    public static final List<String> CALENDAR_VIEW_OPTIONS = List.of("full", "minimal", "stacked");

    // Curated list of common IANA zones offered in Settings. A user whose timezone is null
    // (not one of these) falls back to the server default (app.timezone). Display order in the
    // picker is computed dynamically by current UTC offset (see timezoneChoices), not this order.
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

    public static UserSettings from(User user) {
        return new UserSettings(user.theme, user.pageSize);
    }

    public static int sanitisePageSize(int requested) {
        return PAGE_SIZE_OPTIONS.contains(requested) ? requested : DEFAULT_PAGE_SIZE;
    }

    public static String sanitiseTheme(String requested) {
        return THEME_OPTIONS.contains(requested) ? requested : DEFAULT_THEME;
    }

    public static String sanitiseCalendarView(String requested) {
        return CALENDAR_VIEW_OPTIONS.contains(requested) ? requested : DEFAULT_CALENDAR_VIEW;
    }

    /** Returns the requested zone if it is one of the offered options, else null ("use server default"). */
    public static String sanitiseTimezone(String requested) {
        return requested != null && TIMEZONE_OPTIONS.contains(requested) ? requested : null;
    }

    /** A single option in the timezone picker: form {@code value}, display {@code label}, pre-selected flag. */
    public record TimezoneChoice(String value, String label, boolean selected) {}

    /**
     * Builds the timezone picker options for the given server default zone, evaluated at {@code now}
     * (so UTC offsets reflect the current DST state). The first option is always the server default
     * (value {@code ""} → inherit, labelled with the zone and pre-selected when the user has no
     * override); the rest are the curated zones minus the server default, sorted by current UTC
     * offset (most behind → most ahead).
     *
     * @param selectedTimezone the user's stored timezone (null = inheriting the server default)
     */
    public static List<TimezoneChoice> timezoneChoices(ZoneId serverZone, Instant now, String selectedTimezone) {
        List<TimezoneChoice> choices = new ArrayList<>();
        choices.add(new TimezoneChoice("", zoneLabel(serverZone, now), selectedTimezone == null));

        TIMEZONE_OPTIONS.stream()
                .map(ZoneId::of)
                .filter(zone -> !zone.equals(serverZone))   // never duplicate the server default
                .sorted(Comparator
                        .comparingInt((ZoneId zone) -> zone.getRules().getOffset(now).getTotalSeconds())
                        .thenComparing(ZoneId::getId))
                .forEach(zone -> choices.add(new TimezoneChoice(
                        zone.getId(), zoneLabel(zone, now), zone.getId().equals(selectedTimezone))));

        return choices;
    }

    private static String zoneLabel(ZoneId zone, Instant now) {
        String id = zone.getId();
        String offset = utcOffsetLabel(zone.getRules().getOffset(now));
        // The UTC zone's id already is its offset label — don't render the redundant "UTC (UTC)".
        return id.equals(offset) ? id : id + " (" + offset + ")";
    }

    /** Formats an offset as {@code "UTC"}, {@code "UTC+12"}, {@code "UTC-8"}, or {@code "UTC+5:30"}. */
    static String utcOffsetLabel(ZoneOffset offset) {
        int totalSeconds = offset.getTotalSeconds();
        if (totalSeconds == 0) {
            return "UTC";
        }
        int absSeconds = Math.abs(totalSeconds);
        int hours = absSeconds / 3600;
        int minutes = (absSeconds % 3600) / 60;
        String body = minutes == 0 ? String.valueOf(hours) : hours + ":" + String.format("%02d", minutes);
        return "UTC" + (totalSeconds > 0 ? "+" : "-") + body;
    }
}
