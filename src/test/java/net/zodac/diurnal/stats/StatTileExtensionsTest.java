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

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatTileExtensions#customLabel(StatTile)}: the stats card's half of the "a rename is the user's own text, the catalogue's
 * caption is app chrome" split, the same contract {@link StatSubjectExtensions#actionName(StatSubject)} answers for a subject's name.
 *
 * <p>
 * Null is the CONTRACT here, not an accident: it is what makes {@code partials/stat-tile-row.html}'s {@code .or(msg:statFieldLabel…)} fall through to
 * the translated catalogue word while leaving a user-typed rename untouched.
 */
class StatTileExtensionsTest {

    @Test
    void renamedTile_keepsTheUsersOwnCaption() {
        assertThat(StatTileExtensions.customLabel(tile("Runs this year", true)))
            .as("a renamed stat shows the user's own text, which is never translated")
            .isEqualTo("Runs this year");
    }

    @Test
    void catalogueTile_isNullSoTheCaptionFallsBackToTheTranslatedWord() {
        assertThat(StatTileExtensions.customLabel(tile("Total count", false)))
            .as("a tile still carrying the catalogue's caption yields null, which is what makes the template's .or(msg:…) fire")
            .isNull();
    }

    @Test
    void renamedTile_keepsAnEmptyCaptionRatherThanFallingThrough() {
        assertThat(StatTileExtensions.customLabel(tile("", true)))
            .as("labelIsCustom is what decides, not whether the label happens to be blank")
            .isEmpty();
    }

    private static StatTile tile(final String label, final boolean labelIsCustom) {
        return new StatTile("total-count", label, labelIsCustom, "12", "", false, "text-ink", false, 0L, 0L, 0, 0L, 0L, 0L);
    }
}
