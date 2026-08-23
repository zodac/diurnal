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

import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextOutcomeExtensions;
import org.jspecify.annotations.Nullable;

/**
 * Why a {@link ProfileService} preference update was rejected - ten distinct causes across ten different methods that used to share one opaque,
 * English-only {@code String} on {@code ProfileResult.Invalid}. Carried structured so the API resource can still word it in English (unchanged,
 * {@link ProfileService#message(ProfileRejection)}) while the web resource resolves a translated sentence, the same split every other
 * {@code *Result} in this pass uses.
 *
 * <p>
 * {@link InvalidTextField} covers BOTH the display-name and stat-name pipeline rejections: {@code TextOutcome.Failure} already names its own field
 * ({@code failure.field().key()}), so one variant reuses {@code partials/text-failure-message.html} exactly as {@code ActionResult.InvalidName}/
 * {@code NoteResult.Invalid} do, rather than needing two near-identical variants.
 */
public sealed interface ProfileRejection
    permits ProfileRejection.InvalidTheme, ProfileRejection.InvalidFont, ProfileRejection.InvalidLanguage, ProfileRejection.InvalidCalendarView,
    ProfileRejection.InvalidNoteColour, ProfileRejection.InvalidTimezone, ProfileRejection.InvalidWeekStart, ProfileRejection.InvalidPageSize,
    ProfileRejection.InvalidDecimalPlaces, ProfileRejection.InvalidTextField {

    /**
     * Returns the English reason this profile update was rejected.
     *
     * @return the rejection reason
     */
    String rejectionReason();

    /**
     * Returns the data required to render the profile rejection banner.
     *
     * @return the profile rejection banner
     */
    ProfileRejectionBanner banner();

    /**
     * Data required to render a profile rejection banner.
     *
     * @param kind          the template kind
     * @param allowedValues the allowed values, or {@code null} when the rejection has no allowed-values list
     */
    record ProfileRejectionBanner(String kind, @Nullable String allowedValues) {

    }

    /**
     * An unrecognised theme value.
     *
     * @param allowedValues the theme's own storage values, comma-joined - not translated (a technical enumeration token, like a timezone id, not a
     *                      display word)
     */
    record InvalidTheme(String allowedValues) implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return "Theme must be one of: " + allowedValues + ".";
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("theme", allowedValues);
        }
    }

    /**
     * An unrecognised font value.
     *
     * @param allowedValues the font's own storage values, comma-joined - never translated, see {@link InvalidTheme}
     */
    record InvalidFont(String allowedValues) implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return "Font must be one of: " + allowedValues + ".";
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("font", allowedValues);
        }
    }

    /**
     * An unrecognised language value.
     *
     * @param allowedValues the language's own storage values, comma-joined - never translated, see {@link InvalidTheme}
     */
    record InvalidLanguage(String allowedValues) implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return "Language must be one of: " + allowedValues + ".";
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("language", allowedValues);
        }
    }

    /**
     * An unrecognised calendar-view value.
     *
     * @param allowedValues the calendar view's own storage values, comma-joined - never translated, see {@link InvalidTheme}
     */
    record InvalidCalendarView(String allowedValues) implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return "Calendar style must be one of: " + allowedValues + ".";
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("calendarView", allowedValues);
        }
    }

    /**
     * A note colour that is not a {@code #rrggbb} hex value.
     */
    record InvalidNoteColour() implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return UserSettings.NOTE_COLOUR_MESSAGE;
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("noteColour", null);
        }
    }

    /**
     * A timezone that is not one of the offered options.
     */
    record InvalidTimezone() implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return "Timezone must be one of the offered timezone options.";
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("timezone", null);
        }
    }

    /**
     * A week-start day that is not one of the offered options.
     *
     * @param allowedValues the week start's own storage values, comma-joined - never translated, see {@link InvalidTheme}
     */
    record InvalidWeekStart(String allowedValues) implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return "Week start must be one of: " + allowedValues + ".";
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("weekStart", allowedValues);
        }
    }

    /**
     * A page size (general or per-section) outside {@code UserSettings}' accepted range.
     */
    record InvalidPageSize() implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return UserSettings.PAGE_SIZE_RANGE_MESSAGE;
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("pageSize", null);
        }
    }

    /**
     * A decimal-places preference outside {@code UserSettings}' accepted range.
     */
    record InvalidDecimalPlaces() implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return UserSettings.DECIMAL_PLACES_RANGE_MESSAGE;
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("decimalPlaces", null);
        }
    }

    /**
     * A display-name or stat-name submission that broke the shared text-validation pipeline - which field is named by {@code failure.field().key()}.
     *
     * @param failure the rejection
     */
    record InvalidTextField(TextOutcome.Failure failure) implements ProfileRejection {

        @Override
        public String rejectionReason() {
            return TextOutcomeExtensions.message(failure);
        }

        @Override
        public ProfileRejectionBanner banner() {
            return new ProfileRejectionBanner("textField", null);
        }
    }
}
