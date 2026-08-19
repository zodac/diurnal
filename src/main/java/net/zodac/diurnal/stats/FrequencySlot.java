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

import java.util.List;

/**
 * One column of the frequency graph: a single day of a month window, or a single month of a year window, holding one {@link FrequencyBar} per charted
 * action (drawn side by side within the column). A pure data carrier for the {@code partials/stats-chart} template; the hover wording is composed by
 * {@code partials/frequency-slot-tooltip} (a template, not a Java extension - it embeds a translated "N times" per bar, which a plain Java call can
 * never produce; see {@code AppMessages}' own class Javadoc).
 *
 * <p>
 * Every slot of the window gets a column, including the ones with nothing logged (every bar {@code 0}), so the axis stays evenly spaced and a blank
 * run reads as a visible trough rather than being closed up.
 *
 * @param label the short axis caption ({@code 1}-{@code 31} for a day, {@code Jan}-{@code Dec} for a month)
 * @param fullLabel the slot spelled out for the hover bubble ({@code 3 July 2026} / {@code July 2026})
 * @param tipAlign the {@code partials/tooltip} anchor ({@code left}/{@code center}/{@code right}) for this column's hover bubble, so a column near
 *     either end of the axis grows its bubble inward instead of pushing the chart sideways
 * @param bars one bar per charted action, in the order the legend lists them
 */
public record FrequencySlot(String label, String fullLabel, String tipAlign, List<FrequencyBar> bars) {
}
