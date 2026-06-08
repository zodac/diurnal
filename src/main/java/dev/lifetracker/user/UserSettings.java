package dev.lifetracker.user;

import java.util.List;

public record UserSettings(String theme, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final String DEFAULT_THEME = "system";

    public static final List<Integer> PAGE_SIZE_OPTIONS = List.of(5, 10, 25, 50, 100);
    public static final List<String> THEME_OPTIONS = List.of("system", "light", "dark");

    public static final String DEFAULT_CALENDAR_VIEW = "full";
    public static final List<String> CALENDAR_VIEW_OPTIONS = List.of("full", "minimal");

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
}
