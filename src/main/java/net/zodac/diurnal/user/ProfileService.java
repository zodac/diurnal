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

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import net.zodac.diurnal.stats.ActionStatField;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of every profile and preference update — display name, theme, font, calendar view, timezone, page size, decimal places,
 * stats-summary toggle and the "Action stats" arrangement — shared by the Settings page's HTMX endpoints ({@code WebResource}) and the REST API's
 * {@code PATCH /api/v1/users/me} ({@code UserResource}), so a rule added or changed here applies to both surfaces by construction (the
 * {@code AuthenticationService} pattern). The resources only translate the returned {@link ProfileResult} into their medium.
 *
 * <p>
 * Every submitted value is validated and an unrecognised one is <em>rejected</em> (never silently coerced) so the client keeps the previous value;
 * the one deliberate special case is a blank timezone, the explicit "follow the server default" reset. Every rule delegates to the single validators
 * on {@link UserSettings}, the picker enums and {@link ActionStatField}.
 *
 * <p>
 * Callers own the transaction (each endpoint is {@code @Transactional}); this bean only assumes one is active.
 */
@ApplicationScoped
public class ProfileService {

    private static final Logger LOGGER = LogManager.getLogger(ProfileService.class);

    /**
     * Updates the display name, rejecting a blank or out-of-bounds value.
     *
     * @param user        the acting user
     * @param displayName the submitted display name ({@code null} is treated as blank)
     * @return the outcome
     */
    public ProfileResult updateDisplayName(final User user, final @Nullable String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return new ProfileResult.Invalid("Display name cannot be empty.");
        }
        final String stripped = displayName.strip();
        if (!UserSettings.isValidDisplayName(stripped)) {
            return new ProfileResult.Invalid(UserSettings.DISPLAY_NAME_RANGE_MESSAGE);
        }
        user.displayName = stripped;
        user.persist();
        LOGGER.info("Display name changed to '{}' for user {}", user.displayName, user.email);
        return new ProfileResult.Updated();
    }

    /**
     * Updates the theme, rejecting an unrecognised value.
     *
     * @param user  the acting user
     * @param theme the submitted theme value
     * @return the outcome
     */
    public ProfileResult updateTheme(final User user, final @Nullable String theme) {
        if (theme == null || !Theme.isValid(theme)) {
            return new ProfileResult.Invalid("Theme must be one of: " + allowedValues(Theme.values()) + ".");
        }
        return applySetting(user, "Theme", theme, () -> user.theme = theme);
    }

    /**
     * Updates the font, rejecting an unrecognised value.
     *
     * @param user the acting user
     * @param font the submitted font value
     * @return the outcome
     */
    public ProfileResult updateFont(final User user, final @Nullable String font) {
        if (font == null || !Font.isValid(font)) {
            return new ProfileResult.Invalid("Font must be one of: " + allowedValues(Font.values()) + ".");
        }
        return applySetting(user, "Font", font, () -> user.font = font);
    }

    /**
     * Updates the dashboard calendar style, rejecting an unrecognised value.
     *
     * @param user         the acting user
     * @param calendarView the submitted calendar-view value
     * @return the outcome
     */
    public ProfileResult updateCalendarView(final User user, final @Nullable String calendarView) {
        if (calendarView == null || !CalendarView.isValid(calendarView)) {
            return new ProfileResult.Invalid("Calendar style must be one of: " + allowedValues(CalendarView.values()) + ".");
        }
        return applySetting(user, "Calendar view", calendarView, () -> user.calendarView = calendarView);
    }

    /**
     * Updates the timezone, rejecting an unrecognised zone. A blank submission is the explicit "follow the server default" reset (stored as
     * {@code null}).
     *
     * @param user     the acting user
     * @param timezone the submitted IANA timezone id, or blank to follow the server default
     * @return the outcome
     */
    public ProfileResult updateTimezone(final User user, final @Nullable String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return applySetting(user, "Timezone", null, () -> user.timezone = null); // NOPMD: NullAssignment - null IS the server-default state
        }
        if (!UserSettings.isValidTimezone(timezone)) {
            return new ProfileResult.Invalid("Timezone must be one of the offered timezone options.");
        }
        return applySetting(user, "Timezone", timezone, () -> user.timezone = timezone);
    }

    /**
     * Updates the page size, rejecting a non-numeric or out-of-range value.
     *
     * @param user     the acting user
     * @param pageSize the submitted page size (raw form value)
     * @return the outcome
     */
    public ProfileResult updatePageSize(final User user, final @Nullable String pageSize) {
        final Integer parsed = UserSettings.parsePageSize(pageSize);
        if (parsed == null) {
            return new ProfileResult.Invalid(UserSettings.PAGE_SIZE_RANGE_MESSAGE);
        }
        final int value = parsed;
        return applySetting(user, "Page size", value, () -> user.pageSize = value);
    }

    /**
     * Updates the decimal-place preference, rejecting a non-numeric or out-of-range value.
     *
     * @param user          the acting user
     * @param decimalPlaces the submitted decimal-place count (raw form value)
     * @return the outcome
     */
    public ProfileResult updateDecimalPlaces(final User user, final @Nullable String decimalPlaces) {
        final Integer parsed = UserSettings.parseDecimalPlaces(decimalPlaces);
        if (parsed == null) {
            return new ProfileResult.Invalid(UserSettings.DECIMAL_PLACES_RANGE_MESSAGE);
        }
        final int value = parsed;
        return applySetting(user, "Decimal places", value, () -> user.decimalPlaces = value);
    }

    /**
     * Toggles whether the dashboard renders the stats-summary strip.
     *
     * @param user the acting user
     * @param show the submitted value
     * @return the outcome (always {@link ProfileResult.Updated})
     */
    public ProfileResult updateShowStatsSummary(final User user, final boolean show) {
        return applySetting(user, "Show stats summary", show, () -> user.showStatsSummary = show);
    }

    /**
     * Updates which per-action stats show on the Stats page, and in what order: {@code order} is EVERY field key in the arranged order,
     * {@code enabled} the shown subset. Encoded by {@link ActionStatField#encode(List, java.util.Collection)} (disabled fields kept in place, the
     * mandatory field forced enabled), so unknown keys are dropped identically on every surface. A display-only preference.
     *
     * @param user    the acting user
     * @param order   every field key in the arranged order
     * @param enabled the keys of the fields to show
     * @return the outcome (always {@link ProfileResult.Updated})
     */
    public ProfileResult updateStatsFields(final User user, final List<String> order, final List<String> enabled) {
        final String logValue = "order=" + order + " enabled=" + enabled;
        return applySetting(user, "Action stats", logValue, () -> user.statsFields = ActionStatField.encode(order, enabled));
    }

    private static String allowedValues(final PreviewOption[] options) {
        return java.util.Arrays.stream(options).map(PreviewOption::value).collect(java.util.stream.Collectors.joining(", "));
    }

    private static ProfileResult applySetting(final User user, final String settingName, final @Nullable Object newValue, final Runnable mutation) {
        mutation.run();
        user.persist();
        LOGGER.debug("Setting '{}' changed to '{}' for user {}", settingName, newValue, user.email);
        return new ProfileResult.Updated();
    }
}
