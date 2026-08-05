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
 * One drawn bar of the frequency graph: what a single charted action logged in a single slot of the window. A pure data carrier for the
 * {@code partials/stats-chart} template — the drawn height is pre-computed by {@link FrequencyCharts}.
 *
 * <p>
 * Heights are scaled against the tallest bar of the WHOLE chart, not per action, so two actions charted together are directly comparable: a bar twice
 * the height of another means twice the count, whichever action it belongs to.
 *
 * @param subjectName the charted subject's name, named in the column's hover bubble
 * @param subjectColour the charted subject's display colour, which the bar is drawn in
 * @param count the summed count the action logged in the slot
 * @param heightPercent the bar's height as a percentage of the chart's tallest bar ({@code 0} when nothing was logged)
 */
public record FrequencyBar(String subjectName, String subjectColour, long count, int heightPercent) {
}
