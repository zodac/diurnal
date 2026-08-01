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
 * One to {@link FrequencyCharts#MAX_SERIES} actions' logged frequency over a single calendar window, drawn as a grouped bar chart: a month as one
 * column per day, or a year as one column per month, with each charted action contributing one bar to every column. Assembled by
 * {@link FrequencyCharts} and rendered by {@code partials/stats-chart}; the public API returns the same figures as JSON.
 *
 * <p>
 * A pure data carrier. The window navigation is carried as a pre-computed key plus its availability flag rather than as a nullable key, so neither
 * the template nor a client has to reason about how a window is stepped — {@link FrequencyKeys} owns that.
 *
 * @param period the window's period
 * @param periodKey the window's wire key ({@code yyyy-MM} / {@code yyyy})
 * @param periodLabel the window's heading, spelled out ({@code July 2026} / {@code 2026})
 * @param series the charted actions in legend order, the first being the one the graph was opened from
 * @param slots every slot of the window, in calendar order, including the empty ones
 * @param total the summed count across every charted action and every slot
 * @param peak the tallest bar's count ({@code 0} when nothing at all was logged), which every bar's height is scaled against
 * @param hasPrevious whether an earlier window is worth showing (i.e. it is not before the earliest charted action's first logged entry)
 * @param previousKey the key of the window one step earlier
 * @param hasNext whether a later window is worth showing (i.e. it is not in the future)
 * @param nextKey the key of the window one step later
 */
public record FrequencyChart(
    FrequencyPeriod period,
    String periodKey,
    String periodLabel,
    List<FrequencySeries> series,
    List<FrequencySlot> slots,
    long total,
    long peak,
    boolean hasPrevious,
    String previousKey,
    boolean hasNext,
    String nextKey
) {
}
