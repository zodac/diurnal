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
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class FrequencyChartExtensionsTest {

    private static final UUID FIRST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID THIRD = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static FrequencyChart chartOf(final UUID... actionIds) {
        final List<FrequencySeries> series = Stream.of(actionIds)
            .map(id -> new FrequencySeries(id, "Action", "#64748b", 1L, !id.equals(actionIds[0])))
            .toList();
        return new FrequencyChart(FrequencyPeriod.MONTH, "2026-07", "July 2026", series, List.of(), 1L, 1L, false, "2026-06", false, "2026-08");
    }

    @Test
    void canCompare_belowTheLimit_offersThePicker() {
        assertThat(FrequencyChartExtensions.canCompare(chartOf(FIRST)))
            .as("one charted action leaves room for two more")
            .isTrue();
        assertThat(FrequencyChartExtensions.canCompare(chartOf(FIRST, SECOND)))
            .as("two charted actions leave room for one more")
            .isTrue();
    }

    @Test
    void canCompare_atTheLimit_hidesThePicker() {
        assertThat(FrequencyChartExtensions.canCompare(chartOf(FIRST, SECOND, THIRD)))
            .as("a full chart must not offer to add a fourth action")
            .isFalse();
    }

    @Test
    void compareIds_singleAction_isEmpty() {
        assertThat(FrequencyChartExtensions.compareIds(chartOf(FIRST)))
            .as("nothing is being compared against, so there is no comparison state to echo back")
            .isEmpty();
    }

    @Test
    void compareIds_omitsThePrimaryAndKeepsLegendOrder() {
        assertThat(FrequencyChartExtensions.compareIds(chartOf(FIRST, SECOND, THIRD)))
            .as("only the compared actions ride the wrapper, in the order they were added")
            .isEqualTo(SECOND + "," + THIRD);
    }

    @Test
    void candidatesUrl_carriesThePrimaryAndEveryComparison() {
        assertThat(FrequencyChartExtensions.candidatesUrl(chartOf(FIRST, SECOND)))
            .as("the picker must know everything already charted, or it would offer it again")
            .isEqualTo("/internal/stats/chart/" + FIRST + "/candidates?compare=" + SECOND);
    }

    @Test
    void candidatesUrl_singleAction_carriesNoQueryStringAtAll() {
        assertThat(FrequencyChartExtensions.candidatesUrl(chartOf(FIRST)))
            .as("with nothing compared yet the URL should not trail an empty query string")
            .isEqualTo("/internal/stats/chart/" + FIRST + "/candidates");
    }
}
