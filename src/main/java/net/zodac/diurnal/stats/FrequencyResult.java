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

import java.util.UUID;

/**
 * The outcome of a frequency-chart lookup by {@link StatsService}: the caller (the web UI's HTMX fragment resource or the public REST API) maps each
 * case to its own response medium — a rendered chart partial for HTMX, a JSON DTO plus status code for the API. Follows the sealed-result pattern
 * every other shared use case uses, so neither surface can drift on which windows it accepts.
 *
 * <p>
 * The two rejection cases are deliberately distinct rather than one generic "bad request": a malformed window is only reachable by hand-editing a
 * request, and telling the two apart is what makes that debuggable from the response alone.
 */
sealed interface FrequencyResult permits FrequencyResult.Charted, FrequencyResult.UnknownPeriod, FrequencyResult.UnknownWindow,
    FrequencyResult.TooManyActions, FrequencyResult.DuplicateAction, FrequencyResult.NotLogged, FrequencyResult.NotOwned {

    /**
     * The lookup succeeded; {@link #chart()} is the assembled chart for the requested window.
     *
     * @param chart the assembled chart
     */
    record Charted(FrequencyChart chart) implements FrequencyResult {

    }

    /**
     * The requested {@code period} is not one of the offered windows. The submitted value is echoed back so the caller can name it in the error.
     *
     * @param submitted the rejected period value
     */
    record UnknownPeriod(String submitted) implements FrequencyResult {

    }

    /**
     * The requested {@code at} window is not a well-formed key for the requested period ({@code yyyy-MM} for a month, {@code yyyy} for a year). The
     * submitted value is echoed back so the caller can name it in the error.
     *
     * @param submitted the rejected window key
     */
    record UnknownWindow(String submitted) implements FrequencyResult {

    }

    /**
     * More actions were requested than may be charted together (see {@code FrequencyCharts.MAX_SERIES}).
     *
     * @param submitted the number of actions requested
     * @param maximum the most that may be charted together
     */
    record TooManyActions(int submitted, int maximum) implements FrequencyResult {

    }

    /**
     * The same action was requested more than once. Charting an action against itself is meaningless, and silently collapsing the duplicate would
     * quietly return a different chart than was asked for.
     *
     * @param actionId the repeated action's id
     */
    record DuplicateAction(UUID actionId) implements FrequencyResult {

    }

    /**
     * A comparison action has never been logged, so it could only ever contribute a flat, empty series. Only applies to the actions being compared
     * AGAINST: the graph's own action is charted whatever its history, because an empty chart for it is a meaningful (and reachable) answer.
     *
     * @param actionId the never-logged action's id
     */
    record NotLogged(UUID actionId) implements FrequencyResult {

    }

    /**
     * An action does not exist or is not owned by the acting user.
     */
    record NotOwned() implements FrequencyResult {

    }
}
