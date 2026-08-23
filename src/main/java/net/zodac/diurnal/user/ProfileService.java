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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.zodac.diurnal.colour.Colours;
import net.zodac.diurnal.http.NotUiFacing;
import net.zodac.diurnal.stats.StatField;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextOutcomeExtensions;
import net.zodac.diurnal.text.TextValidation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of every profile and preference update — display name, theme, font, calendar view, note colour, timezone, week start, page size,
 * decimal places, stats-summary toggle and the "Action stats" arrangement — shared by the Settings page's HTMX endpoints
 * ({@code SettingsWebResource}) and the REST API's {@code PATCH /api/v1/users/me} ({@code UserResource}), so a rule added or changed here applies to
 * both surfaces by construction (the
 * {@code AuthenticationService} pattern). The resources only translate the returned {@link ProfileResult} into their medium.
 *
 * <p>
 * Every submitted value is validated and an unrecognised one is <em>rejected</em> (never silently coerced) so the client keeps the previous value;
 * the two deliberate special cases are a blank timezone and a blank week start, the explicit "follow the server default"/"follow the account's
 * language" resets. Every rule delegates to the single validators
 * on {@link UserSettings}, the picker enums and {@link StatField}. Free-text values (the display name) go through the shared
 * {@link TextValidation} pipeline, so they obey the same blank/length/content rules as every other text input in the app.
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
        return switch (TextValidation.check(TextFields.DISPLAY_NAME, displayName)) {
            case final TextOutcome.Valid valid -> applyDisplayName(user, valid.value());
            case final TextOutcome.Failure failure -> new ProfileResult.Invalid(new ProfileRejection.InvalidTextField(failure));
        };
    }

    private static ProfileResult applyDisplayName(final User user, final String displayName) {
        user.displayName = displayName;
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
            return new ProfileResult.Invalid(new ProfileRejection.InvalidTheme(allowedValues(Theme.values())));
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
            return new ProfileResult.Invalid(new ProfileRejection.InvalidFont(allowedValues(Font.values())));
        }
        return applySetting(user, "Font", font, () -> user.font = font);
    }

    /**
     * Updates the UI language, rejecting an unrecognised value.
     *
     * @param user     the acting user
     * @param language the submitted language value
     * @return the outcome
     */
    public ProfileResult updateLanguage(final User user, final @Nullable String language) {
        if (language == null || !Language.isValid(language)) {
            return new ProfileResult.Invalid(new ProfileRejection.InvalidLanguage(allowedLanguageValues()));
        }
        return applySetting(user, "Language", language, () -> user.language = language);
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
            return new ProfileResult.Invalid(new ProfileRejection.InvalidCalendarView(allowedValues(CalendarView.values())));
        }
        return applySetting(user, "Calendar view", calendarView, () -> user.calendarView = calendarView);
    }

    /**
     * Updates the colour the user's day notes are shown in, rejecting anything that is not a {@code #rrggbb} hex value. Held to the same
     * {@link Colours#isInvalidHex(String)} rule as an action's colour, and stored exactly as picked: the app renders it unchanged in both themes,
     * and derives a lightened variant only where the marker sits on the calendar's brand fill (see {@link Colours#readableOn(String, String)}).
     *
     * @param user       the acting user
     * @param noteColour the submitted colour
     * @return the outcome
     */
    public ProfileResult updateNoteColour(final User user, final @Nullable String noteColour) {
        if (noteColour == null || Colours.isInvalidHex(noteColour)) {
            return new ProfileResult.Invalid(new ProfileRejection.InvalidNoteColour());
        }
        return applySetting(user, "Note colour", noteColour, () -> user.noteColour = noteColour);
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
            return new ProfileResult.Invalid(new ProfileRejection.InvalidTimezone());
        }
        return applySetting(user, "Timezone", timezone, () -> user.timezone = timezone);
    }

    /**
     * Updates the day the dashboard calendar's week starts on, rejecting an unrecognised day. A blank submission is the explicit "follow the
     * account's language" reset (stored as {@code null}), the same shape a blank timezone takes.
     *
     * @param user      the acting user
     * @param weekStart the submitted week-start value, or blank to follow the account's language
     * @return the outcome
     */
    public ProfileResult updateWeekStart(final User user, final @Nullable String weekStart) {
        if (weekStart == null || weekStart.isBlank()) {
            return applySetting(user, "Week start", null, () -> user.weekStart = null); // NOPMD: NullAssignment - null IS the follow-the-locale state
        }
        if (!WeekStart.isValid(weekStart)) {
            return new ProfileResult.Invalid(new ProfileRejection.InvalidWeekStart(allowedWeekStartValues()));
        }
        return applySetting(user, "Week start", weekStart, () -> user.weekStart = weekStart);
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
            return new ProfileResult.Invalid(new ProfileRejection.InvalidPageSize());
        }
        final int value = parsed;
        return applySetting(user, "Page size", value, () -> user.pageSize = value);
    }

    /**
     * Updates the per-section page-size overrides: {@code sections} holds one {@link PageSection} key per row and {@code values} the page size
     * submitted for it, pairing up by index. The submission carries the user's WHOLE set (both surfaces post every row), so a section left out of it
     * loses any override it had.
     *
     * <p>
     * Every rule is {@link PageSizes#parse(List, List)}: a blank value resets that section to the general "Items per page" preference, an
     * unrecognised section key is dropped, and a non-numeric or out-of-range value is rejected rather than coerced - the same treatment, and the same
     * message, the general page size gets.
     *
     * @param sections the submitted section keys
     * @param values   the submitted page sizes, in the same order as {@code sections}
     * @param user     the acting user
     * @return the outcome: {@link ProfileResult.Invalid} for a rejected value, otherwise {@link ProfileResult.Updated}
     */
    public ProfileResult updatePageSizes(final User user, final List<String> sections, final @Nullable List<String> values) {
        return switch (PageSizes.parse(sections, values)) {
            case final PageSizeOutcome.Failure _ -> new ProfileResult.Invalid(new ProfileRejection.InvalidPageSize());
            case final PageSizeOutcome.Valid valid -> applyPageSizes(user, valid.overrides());
        };
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
            return new ProfileResult.Invalid(new ProfileRejection.InvalidDecimalPlaces());
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
     * Toggles whether the dashboard note box shows its character counter.
     *
     * <p>
     * Display-only, and deliberately not a way to opt out of the bound: an over-long note is refused exactly as before, and the counter reappears
     * while one IS over the bound however this is set - it is the only thing explaining why Save has gone inert.
     *
     * @param user the acting user
     * @param show the submitted value
     * @return the outcome (always {@link ProfileResult.Updated})
     */
    public ProfileResult updateShowNoteCounter(final User user, final boolean show) {
        return applySetting(user, "Show note counter", show, () -> user.showNoteCounter = show);
    }

    /**
     * Updates which per-action stats show on the Stats page, in what order, and under what name: {@code order} is EVERY field key in the arranged
     * order, {@code enabled} the shown subset, and {@code labels} the custom name of each renamed stat (a key with no entry, or a blank name, keeps
     * the built-in one). Encoded by {@link StatField#encode(List, java.util.Collection, Map)} (disabled fields kept in place, the mandatory
     * field forced enabled), so unknown keys are dropped identically on every surface. A display-only preference.
     *
     * <p>
     * A name that breaks any rule of the shared text pipeline - longer than {@link StatField#MAX_LABEL_LENGTH} characters, or holding an
     * invisible or text-direction character - is rejected rather than truncated or cleaned, on BOTH surfaces, so a stat is never captioned with
     * something other than what was typed.
     *
     * @param user    the acting user
     * @param order   every field key in the arranged order
     * @param enabled the keys of the fields to show
     * @param labels  the custom name of each renamed stat, by key
     * @return the outcome: {@link ProfileResult.Invalid} for a rejected name, otherwise {@link ProfileResult.Updated}
     */
    public ProfileResult updateStatsFields(final User user, final List<String> order, final List<String> enabled, final Map<String, String> labels) {
        // The ONE pass over each submitted name: the outcome carries both the verdict and the normalised value, and it is that value which is
        // encoded below - the resource paired the names, and nothing downstream cleans or re-checks them.
        final Map<String, String> names = new LinkedHashMap<>();
        for (final Map.Entry<String, String> submitted : labels.entrySet()) {
            final TextOutcome outcome = TextValidation.check(TextFields.STAT_NAME, submitted.getValue());
            if (!(outcome instanceof TextOutcome.Valid(final String value))) {
                return new ProfileResult.Invalid(new ProfileRejection.InvalidTextField((TextOutcome.Failure) outcome));
            }

            // A name that normalises to nothing is the reset that restores the catalogue label, so it is simply not carried.
            if (!value.isEmpty()) {
                names.put(submitted.getKey(), value);
            }
        }

        // Logs WHICH stats were renamed, never the names themselves: a name is free text that may hold
        // non-ASCII the container console cannot render (see CLAUDE.md), and the keys identify the change.
        final String logValue = "order=" + order + " enabled=" + enabled + " renamed=" + names.keySet();
        return applySetting(user, "Action stats", logValue, () -> user.statsFields = StatField.encode(order, enabled, names));
    }

    private static String allowedValues(final PreviewOption[] options) {
        return java.util.Arrays.stream(options).map(PreviewOption::value).collect(java.util.stream.Collectors.joining(", "));
    }

    // Language does not implement PreviewOption (see its Javadoc), so it needs its own allowed-values join.
    private static String allowedLanguageValues() {
        return java.util.Arrays.stream(Language.values()).map(Language::value).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String allowedWeekStartValues() {
        return java.util.Arrays.stream(WeekStart.values()).map(WeekStart::value).collect(java.util.stream.Collectors.joining(", "));
    }

    private static ProfileResult applyPageSizes(final User user, final @Nullable List<PageSizePref> overrides) {
        return applySetting(user, "Page sizes", PageSizes.describe(overrides), () -> user.pageSizes = overrides);
    }

    /**
     * The English rejection sentence for a {@link ProfileRejection} - what the API resource still calls directly (it stays English by design); the
     * web resource instead resolves a translated sentence via {@code partials/profile-rejection.html} (or, for
     * {@link ProfileRejection.InvalidTextField}, {@code partials/text-failure-message.html}).
     *
     * @param rejection the rejection cause
     * @return the message, in English
     */
    @NotUiFacing(reason = "the /api/v1 400 body; the Settings page renders partials/profile-rejection.html instead")
    public static String message(final ProfileRejection rejection) {
        return switch (rejection) {
            case final ProfileRejection.InvalidTheme invalid -> "Theme must be one of: " + invalid.allowedValues() + ".";
            case final ProfileRejection.InvalidFont invalid -> "Font must be one of: " + invalid.allowedValues() + ".";
            case final ProfileRejection.InvalidLanguage invalid -> "Language must be one of: " + invalid.allowedValues() + ".";
            case final ProfileRejection.InvalidCalendarView invalid -> "Calendar style must be one of: " + invalid.allowedValues() + ".";
            case final ProfileRejection.InvalidNoteColour _ -> UserSettings.NOTE_COLOUR_MESSAGE;
            case final ProfileRejection.InvalidTimezone _ -> "Timezone must be one of the offered timezone options.";
            case final ProfileRejection.InvalidWeekStart invalid -> "Week start must be one of: " + invalid.allowedValues() + ".";
            case final ProfileRejection.InvalidPageSize _ -> UserSettings.PAGE_SIZE_RANGE_MESSAGE;
            case final ProfileRejection.InvalidDecimalPlaces _ -> UserSettings.DECIMAL_PLACES_RANGE_MESSAGE;
            case final ProfileRejection.InvalidTextField invalid -> TextOutcomeExtensions.message(invalid.failure());
        };
    }

    private static ProfileResult applySetting(final User user, final String settingName, final @Nullable Object newValue, final Runnable mutation) {
        mutation.run();
        user.persist();
        LOGGER.debug("Setting '{}' changed to '{}' for user {}", settingName, newValue, user.email);
        return new ProfileResult.Updated();
    }
}
