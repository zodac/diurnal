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

package net.zodac.diurnal.stats;

/**
 * A single rendered Stats-page tile, pre-computed from an {@link ActionStats} for one {@link ActionStatField}. A pure data carrier for the
 * {@code partials/stat-tile} template.
 *
 * @param label the tile caption
 * @param value the primary figure or label to render
 * @param sub the optional secondary caption ({@code ""} for none)
 * @param subNum {@code true} when {@code sub} carries locale-groupable number(s)
 * @param valueClass a utility/colour class for the value (e.g. a trend colour), or {@code "text-ink"}
 * @param date {@code true} for the date-styled "Last performed" tile (smaller, two-line value)
 */
public record StatTile(
    String  label,
    String  value,
    String  sub,
    boolean subNum,
    String  valueClass,
    boolean date
) {
}
