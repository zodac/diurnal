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

import java.io.Serializable;

/**
 * The settings-picker metadata one {@link PreviewOption} carries: everything {@code partials/preview-picker.html} reads off a tile, held as one value
 * so each picker enum declares it once per constant instead of five parallel fields and five accessors.
 *
 * <p>
 * Pure data — the {@link PreviewOption} accessors read straight through to these components. {@link Serializable} because an enum constant is, and
 * each picker constant holds one of these as its only field.
 *
 * @param value        the stable identifier: the radio value posted by the form, persisted for the setting, and rendered into the page
 * @param label        the short human-readable caption shown beneath the preview thumbnail
 * @param title        the heading shown in the full-size preview lightbox
 * @param alt          the alt text describing the preview thumbnail image
 * @param previewImage the base name (no extension) of the WebP preview thumbnail under {@code /img/settings/}
 */
public record OptionPreview(String value, String label, String title, String alt, String previewImage) implements Serializable {

}
