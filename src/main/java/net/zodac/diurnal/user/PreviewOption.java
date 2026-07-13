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

/**
 * A single choice in one of the settings preview-tile pickers (Theme, Font, Calendar style). Implemented by the {@link Theme}, {@link Font} and
 * {@link CalendarView} enums so the settings template can render every picker's tiles from one uniform shape — each tile's radio value, caption,
 * lightbox heading/alt text, and preview thumbnail — by looping the enum's constants (see {@code partials/preview-option.html}).
 */
public interface PreviewOption {

    /**
     * The stable identifier: the radio value posted by the form, persisted for the setting, and rendered into the page.
     *
     * @return the option value
     */
    String value();

    /**
     * The short human-readable caption shown beneath the preview thumbnail.
     *
     * @return the option label
     */
    String label();

    /**
     * The heading shown in the full-size preview lightbox.
     *
     * @return the option title
     */
    String title();

    /**
     * The alt text describing the preview thumbnail image.
     *
     * @return the option image alt text
     */
    String alt();

    /**
     * The base name (no extension) of the WebP preview thumbnail under {@code /img/settings/}.
     *
     * @return the preview image base name
     */
    String previewImage();
}
