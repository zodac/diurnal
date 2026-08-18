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

    @Test
    void normalise_cleanedField_foldsNewlineToSpace() {
        // The behaviour every field but the note keeps: a label that grew a line break has been pasted by accident.
        assertThat(TextFieldExtensions.normalise(TextFields.ACTION_NAME, "Morning\nrun"))
            .as("a cleaned field must flatten a newline, not preserve it")
            .isEqualTo("Morning run");
    }

    // ── normalise, multi-line ─────────────────────────────────────────────────

    @Test
    void normalise_multilineField_keepsItsNewlines() {
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "First line\nSecond line"))
            .as("a multi-line field's line breaks are part of what the user wrote")
            .isEqualTo("First line\nSecond line");
    }

    @Test
    void normalise_multilineField_foldsCarriageReturns() {
        // A browser textarea submits CRLF per the HTML specification, so this is the everyday case, not an edge one.
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "First\r\nSecond\rThird"))
            .as("CRLF and a lone CR must both fold to a bare newline")
            .isEqualTo("First\nSecond\nThird");
    }

    @Test
    void normalise_multilineField_collapsesHorizontalWhitespaceOnly() {
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "a   \t  b\nc     d"))
            .as("horizontal runs collapse to one space; the newline between them survives")
            .isEqualTo("a b\nc d");
    }

    @Test
    void normalise_multilineField_stripsEachLine() {
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "  first  \n   second   "))
            .as("per-line padding must go, so a line's own indentation cannot pad the length")
            .isEqualTo("first\nsecond");
    }

    @Test
    void normalise_multilineField_keepsOneBlankLineBetweenParagraphs() {
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "para one\n\npara two"))
            .as("a single blank line is an ordinary paragraph break and must survive")
            .isEqualTo("para one\n\npara two");
    }

    @Test
    void normalise_multilineField_condensesRunOfBlankLines() {
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "para one\n\n\n\n\npara two"))
            .as("beyond one blank line the run is padding")
            .isEqualTo("para one\n\npara two");
    }

    @Test
    void normalise_multilineField_countsLineOfSpacesAsBlank() {
        // Ordering check: each line is stripped BEFORE the blank-line run is condensed, so a "blank" line of spaces condenses too.
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "para one\n   \n  \n\npara two"))
            .as("a line holding only spaces is a blank line")
            .isEqualTo("para one\n\npara two");
    }

    @Test
    void normalise_multilineField_stripsLeadingAndTrailingNewlines() {
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "\n\n  body  \n\n"))
            .as("the whole value is stripped last, which removes surrounding blank lines")
            .isEqualTo("body");
    }

    @Test
    void normalise_multilineField_cleansEveryControlCharacterButTheNewline() {
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "a" + Character.toString(0x0007) + "b\nc"))
            .as("only the line feed is exempt; every other control character is still cleaned to a space")
            .isEqualTo("a b\nc");
    }

    @Test
    void normalise_multilineField_composesToNfc() {
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "Cafe" + Character.toString(0x0301) + "\nnext"))
            .as("a multi-line value is NFC-composed exactly like a cleaned one")
            .isEqualTo("Café\nnext");
    }

    @Test
    void normalise_multilineField_whitespaceOnly_becomesEmpty() {
        assertThat(TextFieldExtensions.normalise(TextFields.DEFAULT_NOTE, "  \n\n \t \n  "))
            .as("a value of nothing but whitespace and newlines must normalise to empty, so it is rejected as blank")
            .isEmpty();
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
        assertThat(TextFieldExtensions.lengthMessage(TextField.of("initial", "Initial", 1, 1)))
            .as("a one-character bound must never read '1 characters'")
            .isEqualTo("Initial must be at most 1 character.");
    }

    // ── constraints ───────────────────────────────────────────────────────────

    @Test
    void constraints_fieldWithMinimum_publishesBothBounds() {
        final List<TextConstraint> expected = List.of(
            new TextConstraint("minLength", 1),
            new TextConstraint("maxLength", 128));

        assertThat(TextFieldExtensions.constraints(TextFields.PASSWORD))
            .as("unexpected value")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void constraints_optionalField_publishesOnlyTheMaximum() {
        final List<TextConstraint> expected = List.of(new TextConstraint("maxLength", 25));

        assertThat(TextFieldExtensions.constraints(TextFields.STAT_NAME))
            .as("an optional field has no minimum to require")
            .containsExactlyElementsOf(expected);
    }
}
