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

import java.util.Arrays;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * A single choice in one of the settings preview-tile pickers (Theme, Font, Calendar style). Implemented by the {@link Theme}, {@link Font} and
 * {@link CalendarView} enums so the settings template can render every picker's tiles from one uniform shape — each tile's radio value, caption,
 * lightbox heading/alt text, and preview thumbnail — by looping the enum's constants (see {@code partials/preview-option.html}).
 *
 * <p>
 * Each implementing constant supplies one {@link OptionPreview} and the five accessors below read through to it, so a picker enum is a list of
 * constants and nothing else — the three of them previously repeated an identical fifty-line block of parallel fields, accessors and validator.
 *
 * <p>
 * <strong>Only {@link #value()} has a Java caller</strong> ({@code ProfileService.allowedValues}, wording the rejection for an unrecognised
 * submission). The other four are read by NAME out of {@code partials/preview-picker.html}, which forwards each one into
 * {@code partials/preview-option.html}, so an IDE reports them unused. They are what makes this interface worth having: the picker loops
 * {@code PreviewOption[]} and reads the same five names off every option, which is why adding a theme or a font needs no template change.
 *
 * <p>
 * {@link FunctionalInterface} is accurate rather than an invitation: {@link #preview()} is the single abstract method, and saying so is what both
 * PMD and Qodana ask for. The implementations are still only the three picker enums — nothing here is meant to be written as a lambda.
 */
@FunctionalInterface
public interface PreviewOption {

    /**
     * The metadata this option's tile is rendered from.
     *
     * @return the option's preview metadata
     */
    OptionPreview preview();

    /**
     * The stable identifier: the radio value posted by the form, persisted for the setting, and rendered into the page.
     *
     * @return the option value
     */
    default String value() {
        return preview().value();
    }

    /**
     * The short human-readable caption shown beneath the preview thumbnail.
     *
     * @return the option label
     */
    @SuppressWarnings("unused") // read by name from partials/preview-picker.html; no Java caller
    default String label() {
        return preview().label();
    }

    /**
     * The heading shown in the full-size preview lightbox.
     *
     * @return the option title
     */
    @SuppressWarnings("unused") // read by name from partials/preview-picker.html; no Java caller
    default String title() {
        return preview().title();
    }

    /**
     * The alt text describing the preview thumbnail image.
     *
     * @return the option image alt text
     */
    @SuppressWarnings("unused") // read by name from partials/preview-picker.html; no Java caller
    default String alt() {
        return preview().alt();
    }

    /**
     * The base name (no extension) of the WebP preview thumbnail under {@code /img/settings/}.
     *
     * @return the preview image base name
     */
    @SuppressWarnings("unused") // read by name from partials/preview-picker.html; no Java caller
    default String previewImage() {
        return preview().previewImage();
    }

    /**
     * Whether the submitted value matches one of the offered options. Submissions with an unrecognised value are rejected by the caller
     * ({@link ProfileService}) rather than coerced.
     *
     * @param options the picker's offered options
     * @param value   the submitted value (can be {@code null})
     * @return {@code true} when the value is one of the offered options
     */
    static boolean isValid(final PreviewOption[] options, final @Nullable String value) {
        return Arrays.stream(options).anyMatch(option -> option.value().equals(value));
    }

    /**
     * The picker's offered values, joined for the rejection message naming what was allowed. Never translated — these are the stable identifiers, not
     * the {@link #label()} words.
     *
     * @param options the picker's offered options
     * @return the offered values, comma-separated
     */
    static String allowedValues(final PreviewOption[] options) {
        return Arrays.stream(options).map(PreviewOption::value).collect(Collectors.joining(", "));
    }
}
