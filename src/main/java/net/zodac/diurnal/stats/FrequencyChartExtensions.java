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
     * The charted subject the compare picker hangs off: the first series, which is always the subject the chart was opened for.
     *
     * @param chart the chart
     * @return the primary subject's id
     */
    @TemplateExtension
    public static UUID primarySubjectId(final FrequencyChart chart) {
        return chart.series().getFirst().subjectId();
    }

    /**
     * The query the compare picker's candidates URL carries: every currently-charted comparison, so the picker cannot offer an action that is
     * already on the graph. Empty when nothing is being compared yet.
     *
     * <p>
     * Worded here rather than in the template because a nested expression inside a Qute {@code {#include}} string parameter is not interpolated - it
     * would render the literal braces. The URL itself is assembled by {@code AppPaths}, which owns every path in the application; this contributes
     * only the query.
     *
     * @param chart the chart
     * @return the {@code "?compare=…&compare=…"} query, or an empty string when nothing is being compared
     */
    @TemplateExtension
    public static String candidatesQuery(final FrequencyChart chart) {
        final String compared = chart.series().stream()
            .skip(1L)
            .map(series -> "compare=" + series.subjectId())
            .collect(Collectors.joining("&"));
        return compared.isEmpty() ? "" : ("?" + compared);
    }
}
