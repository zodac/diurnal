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

import io.quarkus.qute.TemplateExtension;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The derived, template-facing values over a {@link FrequencyChart} — kept out of the record itself, which holds data only (see the
 * data-record/extensions split in {@code CLAUDE.md}).
 */
public final class FrequencyChartExtensions {

    private FrequencyChartExtensions() {

    }

    /**
     * Whether the chart has room for another action, i.e. whether the "Compare to..." control should be offered at all. Note this only says the chart
     * is not full; the picker itself reports when there is nothing left to add.
     *
     * @param chart the chart
     * @return {@code true} when fewer than {@link FrequencyCharts#MAX_SERIES} actions are charted
     */
    @TemplateExtension
    public static boolean canCompare(final FrequencyChart chart) {
        return chart.series().size() < FrequencyCharts.MAX_SERIES;
    }

    /**
     * The comparison actions (every charted action except the first) as a comma-separated list of ids, echoed onto the rendered chart so
     * {@code stats.js} can read back exactly what is on screen when the user steps the window or flips the period.
     *
     * @param chart the chart
     * @return the compared action ids, comma-separated, or {@code ""} when only one action is charted
     */
    @TemplateExtension
    public static String compareIds(final FrequencyChart chart) {
        return chart.series().stream()
            .skip(1L)
            .map(series -> series.subjectId().toString())
            .collect(Collectors.joining(","));
    }

    /**
     * The URL the compare picker's search box queries for candidate actions: the primary action's candidates endpoint, already carrying every
     * currently-charted comparison so the picker cannot offer an action that is already on the graph. Built here rather than in the template because
     * a nested expression inside a Qute {@code {#include}} string parameter is not interpolated - it would render the literal braces.
     *
     * @param chart the chart
     * @return the candidates endpoint URL for the current selection
     */
    @TemplateExtension
    public static String candidatesUrl(final FrequencyChart chart) {
        final UUID primaryId = chart.series().getFirst().subjectId();
        final String compared = chart.series().stream()
            .skip(1L)
            .map(series -> "compare=" + series.subjectId())
            .collect(Collectors.joining("&"));
        final String base = "/internal/stats/chart/" + primaryId + "/candidates";
        return compared.isEmpty() ? base : (base + '?' + compared);
    }
}
