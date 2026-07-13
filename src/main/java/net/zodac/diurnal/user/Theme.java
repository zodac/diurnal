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
public enum Theme implements PreviewOption { // NOPMD: DataClass - thin settings-picker metadata catalogue; accessor-heavy by design

    /**
     * Follows the operating system's light/dark preference; the default.
     */
    SYSTEM("system", "System", "System theme", "Dashboard split diagonally between light and dark themes", "page-nova-full-system"),

    /**
     * The light colour scheme.
     */
    LIGHT("light", "Light", "Light theme", "Dashboard in light mode", "page-nova-full-light"),

    /**
     * The dark colour scheme.
     */
    DARK("dark", "Dark", "Dark theme", "Dashboard in dark mode", "page-nova-full-dark");

    /**
     * The theme applied when the stored/submitted value is absent or unrecognised.
     */
    public static final Theme DEFAULT = SYSTEM;

    private final String value;
    private final String label;
    private final String title;
    private final String alt;
    private final String previewImage;

    Theme(final String value, final String label, final String title, final String alt, final String previewImage) {
        this.value = value;
        this.label = label;
        this.title = title;
        this.alt = alt;
        this.previewImage = previewImage;
    }

    @Override
    public String value() {
        return value;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public String alt() {
        return alt;
    }

    @Override
    public String previewImage() {
        return previewImage;
    }

    /**
     * Resolves a theme from its stored/submitted value, coercing any unrecognised or {@code null} value to {@link #DEFAULT}.
     *
     * @param value the value to resolve (can be {@code null})
     * @return the matching theme, or {@link #DEFAULT} if none matches
     */
    public static Theme from(final @Nullable String value) {
        return Arrays.stream(values())
            .filter(theme -> theme.value.equals(value))
            .findFirst()
            .orElse(DEFAULT);
    }
}
