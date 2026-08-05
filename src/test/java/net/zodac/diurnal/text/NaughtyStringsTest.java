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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The hostile-input sweep over the shared pipeline, in the spirit of the "big list of naughty strings": every category of text that is known to break
 * an application that stores free text is put through {@link TextValidation} against every readable field in the {@link TextFields} catalogue, and
 * pinned to the answer it must give.
 *
 * <p>
 * The three answers a value can get, and why each category gets the one it does, are documented in {@code TEXT_INPUT.md}:
 *
 * <ul>
 *     <li><b>cleaned</b> - whitespace of every kind, and control characters, which have an obvious readable equivalent</li>
 *     <li><b>rejected</b> - anything invisible, direction-changing or stacked, which has none</li>
 *     <li><b>stored verbatim</b> - markup, SQL, template syntax and emoji, which are ordinary text to this app and are made safe where they are
 *     RENDERED (Qute escapes by default, the calendar writes {@code textContent}) rather than where they are stored</li>
 * </ul>
 */
class NaughtyStringsTest {

    private static final String EMOJI_FLEXED_BICEPS = Character.toString(0x1F4AA);
    private static final String ZERO_WIDTH_JOINER = Character.toString(0x200D);
    private static final String COMBINING_ACUTE = Character.toString(0x0301);

    private static Stream<TextField> readableFields() {
        return TextFields.all().stream().filter(field -> field.normalisation() == Normalisation.CLEANED);
    }

    private static Stream<Arguments> readableFieldsAndInvisibleValues() {
        final List<String> invisible = List.of(
            Character.toString(0x200B),                                          // zero-width space
            Character.toString(0xFEFF),                                          // byte-order mark
            Character.toString(0x00AD),                                          // soft hyphen
            Character.toString(0x202E),                                          // right-to-left override
            Character.toString(0x2066),                                          // left-to-right isolate
            Character.toString(0xE0001),                                         // language tag
            Character.toString(0xE000),                                          // private use
            Character.toString(0x2060),                                           // word joiner
            Character.toString(0x2062),                                           // invisible times
            Character.toString(0xFFF9),                                           // interlinear annotation anchor
            Character.toString(0x3164),                                           // hangul filler - a LETTER that renders as nothing
            Character.toString(0x115F),                                           // hangul choseong filler
            Character.toString(0xFFA0),                                           // halfwidth hangul filler
            Character.toString(0x17B4),                                           // khmer inherent vowel
            Character.toString(0x2800),                                           // blank braille pattern
            Character.toString(0xFFFF),                                           // noncharacter
            Character.toString(0xFDD0),                                           // noncharacter, from the reserved block
            new String(Character.toChars(0x1FFFE)),                               // noncharacter, on an astral plane
            "\uD800",                                                            // unpaired high surrogate
            // Zalgo. One mark more than the bound would NOT be enough: normalisation composes the first mark onto the letter before it, so the run
            // that the rule then sees is one shorter than the one that was submitted.
            COMBINING_ACUTE.repeat(TextRules.MAX_CONSECUTIVE_MARKS + 2));

        return readableFields().flatMap(field -> invisible.stream().map(value -> Arguments.of(field, value)));
    }

    // A value the field will accept, made hostile by the given insertion. Kept short enough for the smallest bound in the catalogue.
    private static String hostileValue(final TextField field, final String insertion) {
        return field.rules().contains(TextRules.EMAIL_SHAPE)
            ? "us" + insertion + "er@diurnal.example.com"
            : "ru" + insertion + "n";
    }

    // ── rejected: invisible, direction-changing and stacked characters ────────

    @ParameterizedTest
    @MethodSource("readableFieldsAndInvisibleValues")
    void check_invisibleCharacter_isRejectedOnEveryReadableField(final TextField field, final String invisible) {
        assertThat(TextValidation.check(field, hostileValue(field, invisible)))
            .as("every readable field must reject a value that cannot be seen or that reorders what follows it")
            .isInstanceOf(TextOutcome.RuleFailed.class);
    }

    @Test
    void check_zeroWidthSpace_cannotImpersonateAnotherName() {
        // The whole point of the rule: this renders as "admin" but is a different stored value, so it would defeat the duplicate-name check.
        assertThat(TextValidation.check(TextFields.ACTION_NAME, "ad" + Character.toString(0x200B) + "min"))
            .as("two values that render identically must not both be storable")
            .isEqualTo(new TextOutcome.RuleFailed(TextFields.ACTION_NAME, TextRules.NO_INVISIBLE_CHARACTERS));
    }

    @Test
    void check_nameOfOnlyInvisibleLetters_isRejected() {
        // The "blank name" trick: the hangul filler is a LETTER by category, so it escapes a format-character check, yet renders as nothing. Before
        // it was named explicitly, this was an accepted three-character display name that showed up as an empty space in the navbar.
        assertThat(TextValidation.check(TextFields.DISPLAY_NAME, Character.toString(0x3164).repeat(3)))
            .as("a name that renders as nothing must be rejected, whatever category its characters are in")
            .isEqualTo(new TextOutcome.RuleFailed(TextFields.DISPLAY_NAME, TextRules.NO_INVISIBLE_CHARACTERS));
    }

    @Test
    void check_rejection_isWordedForTheUser() {
        final TextOutcome outcome = TextValidation.check(TextFields.DISPLAY_NAME, "ad" + Character.toString(0x202E) + "min");

        assertThat(TextOutcomeExtensions.message((TextOutcome.Failure) outcome))
            .as("a rejection must be explained in terms of the field, not of the rule that fired")
            .isEqualTo("Display name cannot contain invisible or text-direction characters.");
    }

    // ── cleaned: whitespace of every kind, and control characters ─────────────

    @ParameterizedTest
    @ValueSource(ints = {
        0x00A0,     // no-break space
        0x2003,     // em space
        0x3000,     // ideographic space
        0x2028,     // line separator
        0x2029,     // paragraph separator
        0x0085,     // next line
        0x000B,     // vertical tab
        0x0000      // null, which PostgreSQL cannot store at all
    })
    void check_whitespaceOrControl_isCleanedToOneSpace(final int codePoint) {
        final String value = "Morning" + new String(Character.toChars(codePoint)) + "run";

        assertThat(TextValidation.check(TextFields.ACTION_NAME, value))
            .as("every kind of space and control character must be folded onto a plain space")
            .isEqualTo(new TextOutcome.Valid("Morning run"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x00A0, 0x2003, 0x3000, 0x2028})
    void check_valueOfOnlyExoticWhitespace_isBlank(final int codePoint) {
        // Java's \s is ASCII-only, so before the pipeline was made Unicode-aware this was an accepted, entirely invisible name.
        final String value = new String(Character.toChars(codePoint)).repeat(3);

        assertThat(TextValidation.check(TextFields.DISPLAY_NAME, value))
            .as("a value that is nothing but exotic whitespace must be rejected as blank")
            .isInstanceOf(TextOutcome.Blank.class);
    }

    @Test
    void check_logForgingPayload_isFlattenedToOneLine() {
        // The display name is written to the application log, so a name carrying a CR/LF could otherwise forge a second, fake log entry. Both are
        // control characters, so the cleaning pass already neutralises them - this pins that it stays that way.
        assertThat(TextValidation.check(TextFields.DISPLAY_NAME, "Ada\r\n2026-01-01 ADMIN login"))
            .as("a name must never be able to introduce a line break into the log")
            .isEqualTo(new TextOutcome.Valid("Ada 2026-01-01 ADMIN login"));
    }

    @Test
    void check_terminalEscapeSequence_isNeutralised() {
        // ESC is a control character, so a name cannot carry an ANSI colour sequence into a terminal reading the logs.
        assertThat(TextValidation.check(TextFields.ACTION_NAME, "a" + Character.toString(0x001B) + "[31mRED"))
            .as("an escape sequence must not survive into anything that renders the value")
            .isEqualTo(new TextOutcome.Valid("a [31mRED"));
    }

    @Test
    void check_valueThatNormalisationLengthens_isMeasuredAfterwards() {
        // NFC usually shortens, but for a handful of characters it EXPANDS: U+0958 decomposes to two code points. Measuring before normalising would
        // let a value through that no longer fits the column it is about to be written to.
        final String expanding = Character.toString(0x0958);
        final String name = expanding.repeat(TextFields.ACTION_NAME_MAX_LENGTH);

        assertThat(TextValidation.check(TextFields.ACTION_NAME, name))
            .as("length must be measured on the normalised value, which can be LONGER than what was submitted")
            .isInstanceOf(TextOutcome.TooLong.class);
    }

    // ── accepted: emoji, in every form ────────────────────────────────────────

    @ParameterizedTest
    @MethodSource("emojiValues")
    void check_emoji_isAcceptedUnchanged(final String emoji) {
        assertThat(TextValidation.check(TextFields.ACTION_NAME, "Gym " + emoji))
            .as("an emoji is ordinary text to this app, and must survive the pipeline byte for byte")
            .isEqualTo(new TextOutcome.Valid("Gym " + emoji));
    }

    private static Stream<String> emojiValues() {
        return Stream.of(
            EMOJI_FLEXED_BICEPS,
            Character.toString(0x1F469) + ZERO_WIDTH_JOINER + Character.toString(0x1F466),     // a family, joined
            Character.toString(0x1F44D) + Character.toString(0x1F3FD),                         // a skin-tone modifier
            Character.toString(0x1F1F3) + Character.toString(0x1F1FF),                         // a flag, as two regional indicators
            "1" + Character.toString(0xFE0F) + Character.toString(0x20E3));                    // a keycap
    }

    @Test
    void check_emoji_isMeasuredInCodePointsNotUtf16Units() {
        // Every emoji here is two UTF-16 units, so a String.length() bound would reject this at half its apparent length.
        final String name = EMOJI_FLEXED_BICEPS.repeat(TextFields.ACTION_NAME_MAX_LENGTH);

        assertThat(TextValidation.check(TextFields.ACTION_NAME, name))
            .as("a name of emoji must be measured the way a reader counts it")
            .isInstanceOf(TextOutcome.Valid.class);
    }

    @Test
    void check_emojiOverTheBound_isStillRejected() {
        final String name = EMOJI_FLEXED_BICEPS.repeat(TextFields.ACTION_NAME_MAX_LENGTH + 1);

        assertThat(TextValidation.check(TextFields.ACTION_NAME, name))
            .as("counting in code points must not become a way past the bound")
            .isInstanceOf(TextOutcome.TooLong.class);
    }

    // ── accepted: markup, SQL and template syntax, stored verbatim ────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "<script>alert(1)</script>",
        "\"><img src=x onerror=alert(1)>",
        "'; DROP TABLE users;--",
        "' OR '1'='1",
        "{7*7} {#let} {inject:appInfo}",
        "${7*7}",
        "%s%n%d",
        "../../etc/passwd",
        "\\u0000"
    })
    void check_injectionPayload_isStoredAsOrdinaryText(final String payload) {
        // Deliberately NOT rejected or escaped here: a name is data, and is made safe where it is RENDERED. Escaping on the way in would show the
        // user something other than what they typed, and would double-escape the moment a second surface was added.
        assertThat(TextValidation.check(TextFields.ACTION_NAME, payload))
            .as("a payload is ordinary text to the pipeline, and must not be silently rewritten")
            .isEqualTo(new TextOutcome.Valid(payload));
    }

    // ── homoglyphs and lookalikes, which are deliberately allowed ─────────────

    @ParameterizedTest
    @ValueSource(strings = {
        "аdmin",                 // a Cyrillic 'a'
        "ＡＤＭＩＮ",              // full-width letters
        "①②③",                  // circled digits
        "مرحبا",              // Arabic, which is right-to-left without any override
        "˙ɐnbᴉlɐ",               // upside-down text
        "𝕿𝖍𝖊",                  // fraktur letters
        "𝕋𝕙𝕖",                  // double-struck letters
        "🅃🄷🄴",                  // enclosed alphanumerics
        "ᴛʜᴇ",                  // small capitals
        "ᚠᛇᚻ",                  // runes
        "﷽",                     // a single code point rendered about ten characters wide
        "١٢٣"                   // Arabic-Indic digits
    })
    void check_lookalikeText_isAccepted(final String value) {
        // Every one of these is legitimate text in some script. A name is not a security boundary here (it is scoped to one account and is never
        // used to authenticate), so mixed-script confusables are accepted rather than policed.
        assertThat(TextValidation.check(TextFields.ACTION_NAME, value))
            .as("a lookalike character is real text in some script, and is not the pipeline's to reject")
            .isEqualTo(new TextOutcome.Valid(value));
    }

    // ── real text in the scripts a future translation would target ───────────

    @ParameterizedTest
    @ValueSource(strings = {
        "المشي اليومي",           // Arabic
        "اَلْمَشْيُ",                  // Arabic with full harakat
        "پیاده‌روی",                // Persian, which spells this compound with a mandatory zero-width NON-JOINER
        "روزانہ چہل قدمی",         // Urdu
        "הליכה יומית",            // Hebrew
        "הֲלִיכָה",                  // Hebrew with nikud
        "וּֽלְמִקְוֵ֥ה",                 // Biblical Hebrew, which stacks points, meteg and cantillation on one letter
        "เดินทุกวัน",               // Thai
        "रोज़ाना सैर",               // Devanagari
        "தினசரி நடை",              // Tamil
        "매일 걷기",                // Korean
        "毎日の散歩",               // Japanese
        "每日散步",                 // Chinese
        "đi bộ hằng ngày",       // Vietnamese
        "καθημερινό περπάτημα",  // Greek
        "ежедневная прогулка",   // Russian
        "المشي (5km)"             // mixed direction, with no bidi control character
    })
    void check_realTextInEveryScript_isAccepted(final String value) {
        // The i18n guard. These are ordinary words, not hostile input: a content rule that rejects one of them has stopped someone writing in their
        // own language, which is a far worse failure than anything the rules above prevent.
        assertThat(TextValidation.check(TextFields.ACTION_NAME, value))
            .as("ordinary text in a supported script must never be rejected")
            .isEqualTo(new TextOutcome.Valid(value));
    }

    // ── the secret, which is exempt from all of it ────────────────────────────

    @ParameterizedTest
    @MethodSource("invisibleValues")
    void check_password_acceptsWhatEveryReadableFieldRejects(final String invisible) {
        // A password is never rendered, never compared against another user's, and is stored only as a hash - so the reasons the shared rules exist
        // do not apply, and applying them would lock out anyone whose existing password holds one of these.
        final String password = "hunter2" + invisible;

        assertThat(TextValidation.check(TextFields.PASSWORD, password))
            .as("a secret carries no content rules, and is never cleaned")
            .isEqualTo(new TextOutcome.Valid(password));
    }

    private static Stream<String> invisibleValues() {
        return readableFieldsAndInvisibleValues()
            .map(arguments -> (String) arguments.get()[1])
            .distinct();
    }
}
