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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TextRulesTest {

    private static final String ZERO_WIDTH_JOINER = Character.toString(0x200D);
    private static final String ZERO_WIDTH_NON_JOINER = Character.toString(0x200C);
    private static final String VARIATION_SELECTOR_16 = Character.toString(0xFE0F);
    private static final String COMBINING_ACUTE = Character.toString(0x0301);
    private static final String KEYCAP_ENCLOSER = Character.toString(0x20E3);

    // ── invisible and text-direction characters ───────────────────────────────

    @ParameterizedTest
    @ValueSource(ints = {
        0x200B,     // zero-width space
        0xFEFF,     // byte-order mark
        0x00AD,     // soft hyphen
        0x202E,     // right-to-left override
        0x202D,     // left-to-right override
        0x2066,     // left-to-right isolate
        0x2069,     // pop directional isolate
        0x200E,     // left-to-right mark
        0x200F,     // right-to-left mark
        0xE0001,    // language tag
        0xE0065,    // tag character 'e'
        0xE000,     // private use
        0xD800,     // unpaired high surrogate
        0x2060,     // word joiner
        0x2062,     // invisible times
        0xFFF9,     // interlinear annotation anchor
        0x3164,     // hangul filler - a LETTER by category, so only the explicit list catches it
        0x115F,     // hangul choseong filler
        0x1160,     // hangul jungseong filler
        0xFFA0,     // halfwidth hangul filler
        0x17B4,     // khmer inherent vowel aq
        0x17B5,     // khmer inherent vowel aa
        0x2800,     // blank braille pattern
        0xFFFE,     // noncharacter
        0xFFFF,     // noncharacter
        0xFDD0,     // noncharacter, from the reserved block
        0xFDEF,     // noncharacter, at the end of the reserved block
        0x1FFFE,    // noncharacter, on an astral plane
        0x10FFFF    // noncharacter, the last code point of all
    })
    void noInvisibleCharacters_rejectsAnInvisibleCodePoint(final int codePoint) {
        final String value = "ad" + new String(Character.toChars(codePoint)) + "min";

        assertThat(TextRules.NO_INVISIBLE_CHARACTERS.accepts().test(value))
            .as("a character that renders as nothing, or reorders what follows it, must be rejected")
            .isFalse();
    }

    @Test
    void noInvisibleCharacters_acceptsTheZeroWidthJoinerBetweenEmoji() {
        // The joiner is what binds a multi-person emoji together, so rejecting it would reject an ordinary emoji.
        final String family = Character.toString(0x1F469) + ZERO_WIDTH_JOINER + Character.toString(0x1F466);

        assertThat(TextRules.NO_INVISIBLE_CHARACTERS.accepts().test(family))
            .as("the zero-width JOINER is the deliberate exception, so emoji keep working")
            .isTrue();
    }

    @Test
    void noInvisibleCharacters_acceptsTheNonJoinerBetweenLetters() {
        // Persian "piyade-ravi" (walking): the non-joiner is a mandatory letter here, not a trick - the word is misspelled without it, and the same
        // holds for Urdu and Pashto. Rejecting it would refuse to store a Persian speaker's own language.
        final String persian = "پیاده" + ZERO_WIDTH_NON_JOINER + "روی";

        assertThat(TextRules.NO_INVISIBLE_CHARACTERS.accepts().test(persian))
            .as("the zero-width NON-JOINER is mandatory orthography in Persian, Urdu and Pashto")
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\u200Cabc",       // leading
        "abc\u200C",       // trailing
        "\u200Dabc",       // leading joiner
        "abc\u200D",       // trailing joiner
        "a\u200C",         // trailing, with only one character to join to
        "\u200Ca",         // leading, with only one character to join to
        "ab\u200C\u200Ccd", // doubled
        "ab \u200C cd",    // a space on both sides
        "ab\u200C cd",     // a letter on the left, a space on the right
        "ab \u200Ccd",     // a space on the left, a letter on the right
        "\u200C"           // nothing but a joiner
    })
    void noInvisibleCharacters_rejectsJoinerThatJoinsNothing(final String value) {
        // A joiner is orthography only BETWEEN two characters. Leading, trailing, doubled or beside a space it is invisible padding, which is what
        // the rest of the rule exists to reject - and is how a name could otherwise be made to sort or compare differently while looking identical.
        assertThat(TextRules.NO_INVISIBLE_CHARACTERS.accepts().test(value))
            .as("a joiner that is not joining two characters is just an invisible character")
            .isFalse();
    }

    @Test
    void noInvisibleCharacters_acceptsVariationSelector() {
        assertThat(TextRules.NO_INVISIBLE_CHARACTERS.accepts().test("1" + VARIATION_SELECTOR_16 + KEYCAP_ENCLOSER))
            .as("a variation selector is a mark, not a format character, so an emoji keycap is accepted")
            .isTrue();
    }

    @Test
    void noInvisibleCharacters_acceptsOrdinaryText() {
        assertThat(TextRules.NO_INVISIBLE_CHARACTERS.accepts().test("Morning run"))
            .as("ordinary text must be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {
        0x2801,     // a braille pattern that is not the blank one
        0xFDCF,     // the code point below the noncharacter block
        0xFDF0,     // the code point above the noncharacter block
        0x1161,     // a real hangul jungseong, one past the filler
        0xFFFD      // the replacement character, which is a visible glyph
    })
    void noInvisibleCharacters_acceptsTheCodePointsBesideTheRejectedOnes(final int codePoint) {
        // The bounds of every rejected range matter: one code point wider and the rule would reject ordinary text.
        assertThat(TextRules.NO_INVISIBLE_CHARACTERS.accepts().test(new String(Character.toChars(codePoint))))
            .as("a character just outside a rejected range must still be accepted")
            .isTrue();
    }

    @Test
    void noInvisibleCharacters_acceptsAnUnassignedCodePoint() {
        // The JDK's Unicode tables lag new emoji releases, so rejecting the unassigned would reject emoji a browser renders today.
        final int unassigned = 0x0378;

        assertThat(TextRules.NO_INVISIBLE_CHARACTERS.accepts().test(new String(Character.toChars(unassigned))))
            .as("an unassigned code point is deliberately not rejected")
            .isTrue();
    }

    // ── stacked combining marks ───────────────────────────────────────────────

    @Test
    void noStackedMarks_acceptsRunAtTheLimit() {
        final String value = "a" + COMBINING_ACUTE.repeat(TextRules.MAX_CONSECUTIVE_MARKS);

        assertThat(TextRules.NO_STACKED_MARKS.accepts().test(value))
            .as("a run of exactly the maximum number of marks must be accepted")
            .isTrue();
    }

    @Test
    void noStackedMarks_rejectsRunOverTheLimit() {
        final String value = "a" + COMBINING_ACUTE.repeat(TextRules.MAX_CONSECUTIVE_MARKS + 1);

        assertThat(TextRules.NO_STACKED_MARKS.accepts().test(value))
            .as("one mark over the maximum must be rejected")
            .isFalse();
    }

    @Test
    void noStackedMarks_countsConsecutiveMarksOnly() {
        // The same number of marks, spread over several letters, is ordinary text in several scripts.
        final String value = ("a" + COMBINING_ACUTE.repeat(TextRules.MAX_CONSECUTIVE_MARKS)).repeat(3);

        assertThat(TextRules.NO_STACKED_MARKS.accepts().test(value))
            .as("the run must reset at every non-mark, so only stacking is rejected")
            .isTrue();
    }

    @Test
    void noStackedMarks_acceptsTextWithoutMarks() {
        assertThat(TextRules.NO_STACKED_MARKS.accepts().test("Morning run"))
            .as("text carrying no marks at all must be accepted")
            .isTrue();
    }

    // ── email shape ───────────────────────────────────────────────────────────

    @Test
    void emailShape_rejectsAnAddressWithoutAnAtSymbol() {
        assertThat(TextRules.EMAIL_SHAPE.accepts().test("not-an-email"))
            .as("unexpected value")
            .isFalse();
    }

    @Test
    void emailShape_acceptsAnAddressWithAnAtSymbol() {
        assertThat(TextRules.EMAIL_SHAPE.accepts().test("user@diurnal.example.com"))
            .as("unexpected value")
            .isTrue();
    }
}
