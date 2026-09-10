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

import org.jspecify.annotations.Nullable;

/**
 * The catalogue of UI colour schemes offered by the "Theme" setting, and the single source of truth for that picker.
 *
 * <p>
 * Each constant pairs the stable {@link #value()} (persisted in {@code users.theme}, posted by the settings form and rendered into the
 * {@code data-theme} attribute on {@code <html>}, from which the client-side bootstrap resolves the {@code .dark} class) with the metadata the
 * settings picker needs: its {@link #label()}, the {@link #title()}/{@link #alt()} for the preview lightbox, and the {@link #previewImage()}
 * thumbnail base name. Declaration order is the picker's display order.
 *
 * <p>
 * <strong>Adding a new theme:</strong> add a constant here (with its supporting CSS/bootstrap handling and a matching preview WebP), and it
 * automatically appears in the settings picker — the template loops these values, so no template change is needed.
 */
public enum Theme implements PreviewOption {

    /**
     * Follows the operating system's light/dark preference; the default.
     */
    SYSTEM(new OptionPreview("system", "System", "System theme", "Dashboard split diagonally between light and dark themes",
        "page-nova-full-system")),

    /**
     * The light colour scheme.
     */
    LIGHT(new OptionPreview("light", "Light", "Light theme", "Dashboard in light mode", "page-nova-full-light")),

    /**
     * The dark colour scheme.
     */
    DARK(new OptionPreview("dark", "Dark", "Dark theme", "Dashboard in dark mode", "page-nova-full-dark"));

    /**
     * The theme applied when the stored/submitted value is absent or unrecognised.
     */
    public static final Theme DEFAULT = SYSTEM;

    private final OptionPreview preview; // NOPMD: NonSerializableClass - an enum serialises by NAME only, so this field is never written

    Theme(final OptionPreview preview) {
        this.preview = preview;
    }

    @Override
    public OptionPreview preview() {
        return preview;
    }

    /**
     * Whether the submitted value matches one of the offered options. Submissions with an unrecognised value are rejected by the caller
     * ({@code ProfileService}) rather than coerced.
     *
     * @param value the submitted value (can be {@code null})
     * @return {@code true} when the value is one of the offered options
     */
    public static boolean isValid(final @Nullable String value) {
        return PreviewOption.isValid(values(), value);
    }
}
