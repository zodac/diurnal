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

package net.zodac.diurnal.action;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActionColours}: the colour suggested by the new-action form's randomise control. The randomness source is stubbed, so every
 * assertion here is about WHICH colours are offered - never about which of them a given call happened to draw.
 */
class ActionColoursTest {

    private static final String BRAND_INDIGO = "#6366f1";
    private static final int GENERATION_ATTEMPTS = 32;

    @Test
    void suggest_noExistingColours_drawsFromTheWholePalette() {
        final CountingRandom random = new CountingRandom(0);

        final String suggested = ActionColours.suggest(List.of(), random);

        assertThat(suggested)
            .as("with nothing in use, the whole palette should be on offer")
            .isEqualTo(ActionColours.PALETTE.getFirst());
        assertThat(random.lastBound)
            .as("unexpected number of candidate colours")
            .isEqualTo(ActionColours.PALETTE.size());
    }

    @Test
    void suggest_pickIsRandomWithinTheCandidates() {
        final int lastIndex = ActionColours.PALETTE.size() - 1;

        assertThat(ActionColours.suggest(List.of(), new CountingRandom(lastIndex)))
            .as("the suggestion should be the palette entry at the drawn index")
            .isEqualTo(ActionColours.PALETTE.get(lastIndex));
    }

    @Test
    void suggest_existingColour_isNotOffered() {
        final String inUse = ActionColours.PALETTE.getFirst();
        final CountingRandom random = new CountingRandom(0);

        final String suggested = ActionColours.suggest(List.of(inUse), random);

        assertThat(suggested)
            .as("a colour already in use should not be suggested")
            .isNotEqualTo(inUse);
        assertThat(random.lastBound)
            .as("the in-use colour should have been dropped from the candidates")
            .isEqualTo(ActionColours.PALETTE.size() - 1);
    }

    @Test
    void suggest_colourSimilarToAnExistingOne_isNotOffered() {
        final String inUse = ActionColours.PALETTE.getFirst();
        // A near-copy of a palette entry: close enough to be indistinguishable, but not the same value.
        final String almostInUse = "#ee4545";
        final CountingRandom random = new CountingRandom(0);

        final String suggested = ActionColours.suggest(List.of(almostInUse), random);

        assertThat(suggested)
            .as("a colour too similar to one in use should not be suggested")
            .isNotEqualTo(inUse);
        assertThat(random.lastBound)
            .as("only the near-copy's palette entry should have been dropped")
            .isEqualTo(ActionColours.PALETTE.size() - 1);
    }

    @Test
    void suggest_colourExactlyAtTheDistanceLimit_isStillOffered() {
        // Exactly MIN_DISTANCE from the first palette entry: the limit is the point at which two colours ARE tellable apart, so the entry stays
        // on offer and every palette colour is still a candidate.
        final CountingRandom random = new CountingRandom(0);

        final String suggested = ActionColours.suggest(List.of("#ef8044"), random);

        assertThat(suggested)
            .as("a palette colour exactly at the distance limit should still be offered")
            .isEqualTo(ActionColours.PALETTE.getFirst());
        assertThat(random.lastBound)
            .as("no palette colour should have been ruled out")
            .isEqualTo(ActionColours.PALETTE.size());
    }

    @Test
    void suggest_everyPaletteColourInUse_generatesFreshColour() {
        // Hue 62, part-way up the saturation (55 + 5) and lightness (45 + 3) bands, which is far enough from every palette colour to be
        // accepted on the first draw.
        final ScriptedRandom random = new ScriptedRandom(62, 5, 3);

        final String suggested = ActionColours.suggest(ActionColours.PALETTE, random);

        assertThat(suggested)
            .as("a fresh colour should be generated rather than a palette colour repeated")
            .isEqualTo("#bfc431")
            .isNotIn(ActionColours.PALETTE);
        assertThat(random.draws)
            .as("an acceptable colour should be returned as soon as it is drawn")
            .isEqualTo(3);
    }

    @Test
    void suggest_generatedColourTooCloseToAnExistingOne_isRedrawn() {
        // Every draw but the last lands 10 away from the colour in use (far too close); the last lands within the limit but further out, so it is
        // the one settled for once the attempts run out.
        final ScriptedRandom random = new ScriptedRandom(210, 0, 0);
        random.finalDraw();

        final String suggested = ActionColours.suggest(withPaletteInUse("#347db2"), random);

        assertThat(suggested)
            .as("the least-similar of the sampled colours should be settled for")
            .isEqualTo("#34b297");
        assertThat(random.draws)
            .as("every generation attempt should have been used before settling")
            .isEqualTo(3 * GENERATION_ATTEMPTS);
    }

    @Test
    void suggest_generatedColourExactlyAtTheDistanceLimit_isAccepted() {
        // #b23470 is exactly MIN_DISTANCE from the drawn colour, which is therefore acceptable on the first draw.
        final ScriptedRandom random = new ScriptedRandom(0, 0, 0);

        final String suggested = ActionColours.suggest(withPaletteInUse("#b23470"), random);

        assertThat(suggested)
            .as("a generated colour exactly at the distance limit should be accepted")
            .isEqualTo("#b23434");
        assertThat(random.draws)
            .as("a colour at the limit should be accepted on the draw it appears")
            .isEqualTo(3);
    }

    @Test
    void palette_holdsNoDuplicates() {
        assertThat(Set.copyOf(ActionColours.PALETTE))
            .as("the palette should hold no duplicate colours")
            .hasSameSizeAs(ActionColours.PALETTE);
    }

    @Test
    void palette_everyColourIsValidHex() {
        for (final String colour : ActionColours.PALETTE) {
            assertThat(ActionValidation.isColourInvalid(colour))
                .as("palette colour " + colour + " should pass the same validation a user-submitted colour does")
                .isFalse();
        }
    }

    @Test
    void palette_everyColourIsDistinctFromTheOthers() {
        // Each entry is offered as an alternative to the others, so any two being indistinguishable would make a suggestion pointless.
        for (final String colour : ActionColours.PALETTE) {
            final List<String> others = ActionColours.PALETTE.stream()
                .filter(other -> !other.equals(colour))
                .toList();
            assertThat(ActionColours.suggest(others, new CountingRandom(0)))
                .as("palette colour " + colour + " should still be offered when every OTHER palette colour is in use")
                .isEqualTo(colour);
        }
    }

    @Test
    void palette_noColourClashesWithTheDefaultOrTheBrandColour() {
        // The default is what an un-randomised action already gets, and the brand indigo fills the calendar's "today" cell - a dot in either
        // colour would be invisible where it matters most.
        final List<String> reserved = List.of(ActionValidation.DEFAULT_COLOUR, BRAND_INDIGO);
        final CountingRandom random = new CountingRandom(0);

        ActionColours.suggest(reserved, random);

        assertThat(random.lastBound)
            .as("no palette colour should have been ruled out by the default or the brand colour")
            .isEqualTo(ActionColours.PALETTE.size());
    }

    private static List<String> withPaletteInUse(final String extraColour) {
        return Stream.concat(ActionColours.PALETTE.stream(), Stream.of(extraColour)).toList();
    }

    private static final class ScriptedRandom implements RandomGenerator {

        private final int[] values;
        private int[] finalValues;
        private int draws;

        private ScriptedRandom(final int... values) {
            this.values = values.clone();
            finalValues = values.clone();
        }

        private void finalDraw() {
            finalValues = new int[] {167, 0, 0}.clone();
        }

        @Override
        public int nextInt(final int bound) {
            final int index = draws % values.length;
            final boolean isFinalDraw = draws >= (3 * GENERATION_ATTEMPTS) - values.length;
            draws++;
            return Math.min(isFinalDraw ? finalValues[index] : values[index], bound - 1);
        }

        @Override
        public long nextLong() {
            return 0L;
        }
    }

    private static final class CountingRandom implements RandomGenerator {

        private final int fixedValue;
        private int lastBound;

        private CountingRandom(final int fixedValue) {
            this.fixedValue = fixedValue;
        }

        @Override
        public int nextInt(final int bound) {
            lastBound = bound;
            return Math.min(fixedValue, bound - 1);
        }

        @Override
        public long nextLong() {
            return fixedValue;
        }
    }
}
