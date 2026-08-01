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
 * One action charted on the frequency graph, as shown in its legend: the action's identity plus what it totalled over the whole window. A pure data
 * carrier; the per-slot figures live on {@link FrequencySlot}.
 *
 * <p>
 * The first series is the action whose card the graph was opened from and is never removable; the rest were added through the compare picker.
 *
 * @param actionId the action's id
 * @param actionName the action's name
 * @param actionColour the action's display colour, which its bars are drawn in
 * @param total the action's summed count across the whole window
 * @param removable whether the legend offers a control to drop this series (false for the action the graph was opened from)
 */
public record FrequencySeries(UUID actionId, String actionName, String actionColour, long total, boolean removable) {
}
