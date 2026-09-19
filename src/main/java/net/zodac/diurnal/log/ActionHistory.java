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

package net.zodac.diurnal.log;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The whole-history summary of one action's logs: how much it has ever been logged, and the last day it was. Produced by
 * {@link ActionLog#historyByAction(UUID)} (one instance per action the user has ever logged) and consumed by {@link DayActionOrdering} to order the
 * dashboard's day panel under the {@code mostLogged} and {@code mostRecent} settings. A typed projection, never a positional {@code Object[]} tuple.
 *
 * <p>
 * An action the user has never logged has no row here, rather than a zeroed one - the query aggregates the logs, and there are none to aggregate.
 * {@link DayActionOrdering} treats an absent entry as the bottom of the order, where a never-logged action belongs under both settings.
 *
 * @param actionId the action the summary belongs to
 * @param totalCount the summed {@code count} over every day the action has been logged
 * @param lastLogged the most recent day the action was logged
 */
public record ActionHistory(UUID actionId, long totalCount, LocalDate lastLogged) {
}
