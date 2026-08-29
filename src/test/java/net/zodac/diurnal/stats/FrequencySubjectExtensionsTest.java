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
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The frequency graph's half of the "an action's name is the user's own text, the notes subject's name is app chrome" split - the same contract
 * {@link StatSubjectExtensions#actionName(StatSubject)} answers for a stats card, over the graph's own two view-model records.
 *
 * <p>
 * Null is the CONTRACT here, not an accident: it is what makes the templates' {@code .or(msg:statSubjectNotes)} fall through to the translated word
 * for the notes subject while leaving a user-typed action name untouched.
 */
class FrequencySubjectExtensionsTest {

    private static final String ACTION_COLOUR = "#6366f1";

    @Test
    void seriesForAnAction_keepsTheUsersOwnName() {
        final FrequencySeries series = new FrequencySeries(DUMMY_UUID, "Morning run", ACTION_COLOUR, 12L, true);

        assertThat(FrequencySubjectExtensions.actionName(series))
            .as("a legend chip for an action shows the user's own text, which is never translated")
            .isEqualTo("Morning run");
    }

    @Test
    void seriesForNotes_isNullSoTheLegendFallsBackToTheTranslatedWord() {
        final FrequencySeries series = new FrequencySeries(StatSubject.NOTES_ID, "Notes", ACTION_COLOUR, 12L, false);

        assertThat(FrequencySubjectExtensions.actionName(series))
            .as("the notes series yields null, which is what makes the template's .or(msg:statSubjectNotes) fire")
            .isNull();
    }

    @Test
    void barForAnAction_keepsTheUsersOwnName() {
        final FrequencyBar drawnBar = new FrequencyBar(DUMMY_UUID, "Morning run", ACTION_COLOUR, 3L, 50);

        assertThat(FrequencySubjectExtensions.actionName(drawnBar))
            .as("a hover bubble for an action's bar shows the user's own text, which is never translated")
            .isEqualTo("Morning run");
    }

    @Test
    void barForNotes_isNullSoTheTooltipFallsBackToTheTranslatedWord() {
        final FrequencyBar drawnBar = new FrequencyBar(StatSubject.NOTES_ID, "Notes", ACTION_COLOUR, 3L, 50);

        assertThat(FrequencySubjectExtensions.actionName(drawnBar))
            .as("the notes bar yields null, which is what makes the template's .or(msg:statSubjectNotes) fire")
            .isNull();
    }
}
