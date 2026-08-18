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

/**
 * Why a {@link ProfileService} preference update was rejected - nine distinct causes across nine different methods that used to share one opaque,
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
    ProfileRejection.InvalidNoteColour, ProfileRejection.InvalidTimezone, ProfileRejection.InvalidPageSize, ProfileRejection.InvalidDecimalPlaces,
    ProfileRejection.InvalidTextField {

    /**
     * An unrecognised theme value.
     *
     * @param allowedValues the theme's own storage values, comma-joined - not translated (a technical enumeration token, like a timezone id, not a
     *     display word)
     */
    record InvalidTheme(String allowedValues) implements ProfileRejection {

    }

    /**
     * An unrecognised font value.
     *
     * @param allowedValues the font's own storage values, comma-joined - never translated, see {@link InvalidTheme}
     */
    record InvalidFont(String allowedValues) implements ProfileRejection {

    }

    /**
     * An unrecognised language value.
     *
     * @param allowedValues the language's own storage values, comma-joined - never translated, see {@link InvalidTheme}
     */
    record InvalidLanguage(String allowedValues) implements ProfileRejection {

    }

    /**
     * An unrecognised calendar-view value.
     *
     * @param allowedValues the calendar view's own storage values, comma-joined - never translated, see {@link InvalidTheme}
     */
    record InvalidCalendarView(String allowedValues) implements ProfileRejection {

    }

    /**
     * A note colour that is not a {@code #rrggbb} hex value.
     */
    record InvalidNoteColour() implements ProfileRejection {

    }

    /**
     * A timezone that is not one of the offered options.
     */
    record InvalidTimezone() implements ProfileRejection {

    }

    /**
     * A page size (general or per-section) outside {@code UserSettings}' accepted range.
     */
    record InvalidPageSize() implements ProfileRejection {

    }

    /**
     * A decimal-places preference outside {@code UserSettings}' accepted range.
     */
    record InvalidDecimalPlaces() implements ProfileRejection {

    }

    /**
     * A display-name or stat-name submission that broke the shared text-validation pipeline - which field is named by {@code failure.field().key()}.
     *
     * @param failure the rejection
     */
    record InvalidTextField(TextOutcome.Failure failure) implements ProfileRejection {

    }
}
