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
 * The catalogue of dashboard calendar styles offered by the "Calendar style" setting, and the single source of truth for that picker.
 *
 * <p>
 * Each constant pairs the stable {@link #value()} (persisted in {@code users.calendar_view}, posted by the settings form, mirrored onto the calendar
 * wrapper as the {@code d-cal-<value>} class and used by {@code dashboard.js} to pick the event feed and cell renderer) with the metadata the
 * settings picker needs: its {@link #label()}, the {@link #title()}/{@link #alt()} for the preview lightbox, and the {@link #previewImage()}
 * thumbnail base name. Declaration order is the picker's display order.
 *
 * <p>
 * <strong>Adding a new calendar style:</strong> add a constant here (with its {@code d-cal-*} CSS, {@code dashboard.js} render branch and a matching
 * {@code cal-nova-<value>-dark.webp} preview), and it automatically appears in the settings picker — the template loops these values, so no template
 * change is needed.
 */
public enum CalendarView implements PreviewOption { // NOPMD: DataClass - thin settings-picker metadata catalogue; accessor-heavy by design

    /**
     * Bordered cells with a per-day, uncapped list of event labels; the default.
     */
    FULL("full", "Full", "Full calendar", "Dashboard calendar showing events as text", "cal-nova-full-dark"),

    /**
     * A compact date circle with up to four coloured dots per day.
     */
    MINIMAL("minimal", "Minimal", "Minimal calendar", "Dashboard calendar showing a coloured dot per action", "cal-nova-minimal-dark"),

    /**
     * A compact date circle with up to four stacked colour bars per day.
     */
    STACKED("stacked", "Stacked", "Stacked calendar", "Dashboard calendar showing horizontal bars per action", "cal-nova-stacked-dark");

    /**
     * The calendar style applied when the stored/submitted value is absent or unrecognised.
     */
    public static final CalendarView DEFAULT = FULL;

    private final String value;
    private final String label;
    private final String title;
    private final String alt;
    private final String previewImage;

    CalendarView(final String value, final String label, final String title, final String alt, final String previewImage) {
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
     * Whether the submitted value matches one of the offered options. Submissions with an unrecognised value are rejected by the caller
     * ({@code ProfileService}) rather than coerced.
     *
     * @param value the submitted value (can be {@code null})
     * @return {@code true} when the value is one of the offered options
     */
    public static boolean isValid(final @Nullable String value) {
        return Arrays.stream(values()).anyMatch(option -> option.value.equals(value));
    }
}
