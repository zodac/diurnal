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
import java.util.stream.Collectors;
import net.zodac.diurnal.time.Durations;

/**
 * The derived, template-facing wording over a {@link FrequencySlot} — kept out of the record itself, which holds data only (see the
 * data-record/extensions split in {@code CLAUDE.md}).
 */
public final class FrequencySlotExtensions {

    private FrequencySlotExtensions() {

    }

    /**
     * The column's hover text: the slot spelled out, followed by what each charted action logged in it. A single charted action reads on one line
     * ({@code 3 July 2026: 4 times}); two or more put the slot on its own line with one line per action beneath, which the chart's
     * {@code white-space: pre-line} tooltip rule renders as separate lines.
     *
     * <p>
     * Hovering is per COLUMN rather than per bar on purpose: at 31 days by three actions a bar is only a couple of pixels wide, so a per-bar target
     * would be unhittable — and comparing the actions is the whole point of charting them together, which wants them named side by side anyway.
     *
     * <p>
     * This is the only place the chart's figures are worded, and every count goes through {@link Durations#count(long, String)} so a lone entry reads
     * "1 time" rather than "1 times".
     *
     * @param slot the column
     * @return the hover text
     */
    @TemplateExtension
    public static String tooltip(final FrequencySlot slot) {
        if (slot.bars().size() == 1) {
            return slot.fullLabel() + ": " + Durations.count(slot.bars().getFirst().count(), "time");
        }

        return slot.bars().stream()
            .map(bar -> bar.subjectName() + ": " + Durations.count(bar.count(), "time"))
            .collect(Collectors.joining("\n", slot.fullLabel() + "\n", ""));
    }
}
