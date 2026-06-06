package dev.lifetracker.user;

import java.util.List;

/**
 * User-configurable application preferences.
 * <p>
 * Each preference is persisted as a column on the {@link User} entity (mirroring the
 * dark-mode convention). This record centralises the settings shape, defaults and
 * validation so new preferences can be added in a single place as the app grows.
 */
public record UserSettings(boolean darkMode, int pageSize) {

    /** Default number of items shown per page across every paginated list. */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** Selectable page-size values offered on the settings page. */
    public static final List<Integer> PAGE_SIZE_OPTIONS = List.of(10, 25, 50, 100);

    public static UserSettings from(User user) {
        return new UserSettings(user.darkMode, user.pageSize);
    }

    /** Returns the requested page size if it is an allowed option, otherwise the default. */
    public static int sanitisePageSize(int requested) {
        return PAGE_SIZE_OPTIONS.contains(requested) ? requested : DEFAULT_PAGE_SIZE;
    }
}
