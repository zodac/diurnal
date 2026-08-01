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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FrequencySlotExtensionsTest {

    private static FrequencySlot slot(final FrequencyBar... bars) {
        return new FrequencySlot("3", "3 July 2026", "center", List.of(bars));
    }

    private static FrequencyBar bar(final String name, final long count) {
        return new FrequencyBar(name, "#64748b", count, 50);
    }

    @Test
    void tooltip_singleAction_readsOnOneLine() {
        assertThat(FrequencySlotExtensions.tooltip(slot(bar("Running", 4L))))
            .as("a lone charted action needs no name, so the slot and its count read as one line")
            .isEqualTo("3 July 2026: 4 times");
    }

    @Test
    void tooltip_singleEntry_isNotPluralised() {
        assertThat(FrequencySlotExtensions.tooltip(slot(bar("Running", 1L))))
            .as("UI text must never read '1 times'")
            .isEqualTo("3 July 2026: 1 time");
    }

    @Test
    void tooltip_emptySlot_readsAsZeroTimes() {
        assertThat(FrequencySlotExtensions.tooltip(slot(bar("Running", 0L))))
            .as("unexpected value")
            .isEqualTo("3 July 2026: 0 times");
    }

    @Test
    void tooltip_multipleActions_namesEachOnItsOwnLine() {
        assertThat(FrequencySlotExtensions.tooltip(slot(bar("Running", 4L), bar("Yoga", 1L))))
            .as("comparing actions is the point, so each is named with its own count")
            .isEqualTo("3 July 2026\nRunning: 4 times\nYoga: 1 time");
    }

    @Test
    void tooltip_multipleActions_keepsTheLegendOrderAndEmptyOnes() {
        assertThat(FrequencySlotExtensions.tooltip(slot(bar("Running", 0L), bar("Yoga", 2L), bar("Reading", 0L))))
            .as("an action that logged nothing in the slot is still listed, so the comparison is complete")
            .isEqualTo("3 July 2026\nRunning: 0 times\nYoga: 2 times\nReading: 0 times");
    }
}
