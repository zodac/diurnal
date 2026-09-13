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

import static net.zodac.diurnal.DummyValues.DUMMY_UUID;
import static net.zodac.diurnal.DummyValues.OTHER_DUMMY_UUID;
import static net.zodac.diurnal.DummyValues.THIRD_DUMMY_UUID;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FrequencyChartExtensionsTest {

    private static FrequencyChart chartOf(final UUID... actionIds) {
        final List<FrequencySeries> series = Stream.of(actionIds)
            .map(id -> new FrequencySeries(id, "Action", "#64748b", 1L, !id.equals(actionIds[0])))
            .toList();
        return new FrequencyChart(FrequencyPeriod.MONTH, "2026-07", "July 2026", series, List.of(), 1L, 1L, false, "2026-06", false, "2026-08");
    }

    @Test
    void canCompare_belowTheLimit_offersThePicker() {
        assertThat(FrequencyChartExtensions.canCompare(chartOf(DUMMY_UUID)))
            .as("one charted action leaves room for two more")
            .isTrue();
        assertThat(FrequencyChartExtensions.canCompare(chartOf(DUMMY_UUID, OTHER_DUMMY_UUID)))
            .as("two charted actions leave room for one more")
            .isTrue();
    }

    @Test
    void canCompare_atTheLimit_hidesThePicker() {
        assertThat(FrequencyChartExtensions.canCompare(chartOf(DUMMY_UUID, OTHER_DUMMY_UUID, THIRD_DUMMY_UUID)))
            .as("a full chart must not offer to add a fourth action")
            .isFalse();
    }

    @Test
    void compareIds_singleAction_isEmpty() {
        assertThat(FrequencyChartExtensions.compareIds(chartOf(DUMMY_UUID)))
            .as("nothing is being compared against, so there is no comparison state to echo back")
            .isEmpty();
    }

    @Test
    void compareIds_omitsThePrimaryAndKeepsLegendOrder() {
        assertThat(FrequencyChartExtensions.compareIds(chartOf(DUMMY_UUID, OTHER_DUMMY_UUID, THIRD_DUMMY_UUID)))
            .as("only the compared actions ride the wrapper, in the order they were added")
            .isEqualTo(OTHER_DUMMY_UUID + "," + THIRD_DUMMY_UUID);
    }

    @Test
    void primarySubjectId_isTheSubjectTheChartWasOpenedFor() {
        assertThat(FrequencyChartExtensions.primarySubjectId(chartOf(DUMMY_UUID, OTHER_DUMMY_UUID)))
            .as("the compare picker hangs off the first series, which is always the charted subject")
            .isEqualTo(DUMMY_UUID);
    }

    @Test
    void candidatesQuery_carriesEveryComparison() {
        assertThat(FrequencyChartExtensions.candidatesQuery(chartOf(DUMMY_UUID, OTHER_DUMMY_UUID)))
            .as("the picker must know everything already charted, or it would offer it again")
            .isEqualTo("?compare=" + OTHER_DUMMY_UUID);
    }

    @Test
    void candidatesQuery_singleAction_isEmpty() {
        assertThat(FrequencyChartExtensions.candidatesQuery(chartOf(DUMMY_UUID)))
            .as("with nothing compared yet the URL should not trail an empty query string")
            .isEmpty();
    }
}
