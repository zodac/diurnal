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

package net.zodac.diurnal.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextFieldExtensionsTest {

    private static final String EMOJI = Character.toString(0x1F600);

    // ── length ────────────────────────────────────────────────────────────────

    @Test
    void length_countsCodePointsNotUtf16Units() {
        assertThat(TextFieldExtensions.length(EMOJI.repeat(3)))
            .as("three emoji are three characters to a reader, not six")
            .isEqualTo(3);
    }

    @Test
    void length_emptyValue_isZero() {
        assertThat(TextFieldExtensions.length(""))
            .as("unexpected value")
            .isZero();
    }

    // ── normalise ─────────────────────────────────────────────────────────────

    @Test
    void normalise_verbatimField_returnsTheValueUntouched() {
        assertThat(TextFieldExtensions.normalise(TextFields.PASSWORD, "  a  b  "))
            .as("a verbatim field must not be cleaned")
            .isEqualTo("  a  b  ");
    }

    @Test
    void normalise_cleanedField_stripsCollapsesAndComposes() {
        assertThat(TextFieldExtensions.normalise(TextFields.ACTION_NAME, "  a   b  "))
            .as("unexpected value")
            .isEqualTo("a b");
    }

    // ── truncate ──────────────────────────────────────────────────────────────

    @Test
    void truncate_shorterValue_isUnchanged() {
        assertThat(TextFieldExtensions.truncate("abc", 5))
            .as("unexpected value")
            .isEqualTo("abc");
    }

    @Test
    void truncate_valueAtTheBound_isUnchanged() {
        assertThat(TextFieldExtensions.truncate("abcde", 5))
            .as("unexpected value")
            .isEqualTo("abcde");
    }

    @Test
    void truncate_longerValue_isCut() {
        assertThat(TextFieldExtensions.truncate("abcdef", 5))
            .as("unexpected value")
            .isEqualTo("abcde");
    }

    @Test
    void truncate_neverSplitsAnEmojiInHalf() {
        // Cutting by UTF-16 units would leave half an emoji - an unpaired surrogate - in the column.
        assertThat(TextFieldExtensions.truncate(EMOJI.repeat(4), 2))
            .as("unexpected value")
            .isEqualTo(EMOJI.repeat(2));
    }

    // ── lengthMessage ─────────────────────────────────────────────────────────

    @Test
    void lengthMessage_fieldWithMinimum_readsAsRange() {
        assertThat(TextFieldExtensions.lengthMessage(TextFields.DISPLAY_NAME))
            .as("unexpected value")
            .isEqualTo("Display name must be between 2 and 50 characters.");
    }

    @Test
    void lengthMessage_fieldWithoutMinimum_statesOnlyTheMaximum() {
        assertThat(TextFieldExtensions.lengthMessage(TextFields.PASSWORD))
            .as("unexpected value")
            .isEqualTo("Password must be at most 128 characters.");
    }

    @Test
    void lengthMessage_optionalField_statesOnlyTheMaximum() {
        assertThat(TextFieldExtensions.lengthMessage(TextFields.STAT_NAME))
            .as("unexpected value")
            .isEqualTo("Stat name must be at most 25 characters.");
    }

    @Test
    void lengthMessage_maximumOfOne_isSingular() {
        assertThat(TextFieldExtensions.lengthMessage(TextField.of("Initial", 1, 1)))
            .as("a one-character bound must never read '1 characters'")
            .isEqualTo("Initial must be at most 1 character.");
    }

    // ── constraints ───────────────────────────────────────────────────────────

    @Test
    void constraints_fieldWithMinimum_publishesBothBounds() {
        final List<TextConstraint> expected = List.of(
            new TextConstraint("minLength", 1, "At least 1 character"),
            new TextConstraint("maxLength", 128, "At most 128 characters"));

        assertThat(TextFieldExtensions.constraints(TextFields.PASSWORD))
            .as("unexpected value")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void constraints_optionalField_publishesOnlyTheMaximum() {
        final List<TextConstraint> expected = List.of(new TextConstraint("maxLength", 25, "At most 25 characters"));

        assertThat(TextFieldExtensions.constraints(TextFields.STAT_NAME))
            .as("an optional field has no minimum to require")
            .containsExactlyElementsOf(expected);
    }
}
