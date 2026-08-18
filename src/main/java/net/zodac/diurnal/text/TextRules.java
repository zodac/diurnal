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

/**
 * The shared catalogue of {@link TextRule}s, so a content check is written once and referenced by every field that wants it.
 *
 * <p>
 * A rule that should apply to EVERY text input belongs in the defaults of {@link TextField#of(String, String, int, int)} rather than here, so no
 * field can be added that quietly skips it.
 */
public final class TextRules { // NOPMD: DataClass - a catalogue of rule constants; the branching lives in the predicates behind them

    /**
     * The longest run of consecutive combining marks a value may carry. Eight leaves room for every script that genuinely stacks marks - measured
     * against the worst real case, Biblical Hebrew, where one letter can carry a dagesh, a vowel, a meteg and two cantillation marks (five, and six
     * with a second vowel point) - while still stopping the "zalgo" pattern of dozens of diacritics on one letter, which renders as a column of
     * glyphs that overflows the row it is shown in.
     */
    public static final int MAX_CONSECUTIVE_MARKS = 8;

    private static final int ZERO_WIDTH_JOINER = 0x200D;
    private static final int ZERO_WIDTH_NON_JOINER = 0x200C;
    private static final int LINE_FEED = 0x000A;

    private static final int[] BLANK_CHARACTERS = {
        0x115F,     // hangul choseong filler
        0x1160,     // hangul jungseong filler
        0x17B4,     // khmer vowel inherent aq
        0x17B5,     // khmer vowel inherent aa
        0x2800,     // braille pattern blank
        0x3164,     // hangul filler
        0xFFA0      // halfwidth hangul filler
    };

    private static final int NONCHARACTER_PLANE_MASK = 0xFFFE;
    private static final int NONCHARACTER_BLOCK_START = 0xFDD0;
    private static final int NONCHARACTER_BLOCK_END = 0xFDEF;

    /**
     * An email address must contain an {@code @} symbol. Deliberately the weakest possible shape check: the app never asserts an address is
     * deliverable, and every stricter pattern in circulation rejects addresses that are legal.
     */
    public static final TextRule EMAIL_SHAPE = new TextRule("emailShape", value -> value.contains("@"), "must contain an @ symbol.");

    /**
     * A value may not carry a character that renders as nothing, or that changes how the characters around it are laid out. Four families qualify:
     *
     * <ul>
     *     <li>the format, control, surrogate and private-use categories - the zero-width space, the byte-order mark, the soft hyphen, the word
     *     joiner, the bidirectional overrides/isolates/marks, the interlinear annotations, the tag characters, unpaired surrogates and the
     *     private-use area (the two zero-width JOINERS are handled separately - see below)</li>
     *     <li>the {@link #BLANK_CHARACTERS} - code points that are LETTERS or marks by category, and so escape the check above, but render as
     *     nothing: the hangul fillers, the khmer inherent vowels and the blank braille pattern. These are the characters behind the "blank name"
     *     trick on every chat platform</li>
     *     <li>the Unicode noncharacters (U+FDD0-U+FDEF and the last two code points of every plane), which are permanently reserved, will never be
     *     assigned, and render as a fallback box or nothing at all</li>
     * </ul>
     *
     * <p>
     * The reason is that two different values must never render identically: {@code ad<ZWSP>min} is stored as a different name from {@code admin} but
     * is indistinguishable from it on screen, so it defeats the duplicate-name check and can be used to impersonate. A bidirectional override goes
     * further and reverses the text after it, so a name can be made to display as something other than what it is. A name made ONLY of these would
     * pass every length check while showing nothing at all.
     *
     * <p>
     * The two JOINERS - zero-width joiner (U+200D) and zero-width non-joiner (U+200C) - are the deliberate exception, because both are real
     * orthography rather than a trick: the joiner binds the parts of a multi-person emoji, and the non-joiner is a MANDATORY letter in Persian, Urdu
     * and Pashto (the everyday word {@code piyade-ravi} is misspelled without it). They are accepted only where they do that job - BETWEEN two other
     * characters, neither of which is a space or itself invisible - so a leading, trailing, doubled or space-adjacent joiner is still rejected as the
     * invisible padding it is.
     *
     * <p>
     * The residual cost is accepted knowingly: a joiner between two Latin letters renders as nothing, so {@code ad<ZWNJ>min} and {@code admin} look
     * alike. That collision is already reachable through homoglyphs (which this app deliberately allows), an action name is scoped to one account and
     * never authenticates, and the alternative is refusing to store a Persian speaker's own language.
     *
     * <p>
     * Unassigned code points are also deliberately NOT rejected (beyond the noncharacters, which can never become assigned) - the JDK's Unicode
     * tables lag new emoji releases, so rejecting them would reject emoji that a current browser renders perfectly.
     */
    public static final TextRule NO_INVISIBLE_CHARACTERS = new TextRule("noInvisibleCharacters",
        value -> hasNoInvisibleCharacters(value, false), "cannot contain invisible or text-direction characters.");

    /**
     * {@link #NO_INVISIBLE_CHARACTERS}, with the line feed exempted - the rule a {@link Normalisation#MULTILINE} field carries in place of it.
     *
     * <p>
     * A line feed is a {@code Cc} control character, so the rule above rejects it. Every other field never meets one: {@link Normalisation#CLEANED}
     * turns every control character into a space long before the rules run, which is exactly why this exemption has not been needed until now. A
     * multi-line field deliberately keeps its line feeds through normalisation, so without this variant the shared invisible-character policy would
     * reject every note that has a second line.
     *
     * <p>
     * The exemption is the line feed and nothing else: a carriage return has already been folded away by normalisation, and every other invisible
     * character - the zero-width space, the bidirectional overrides, the blank letters, the noncharacters - is still rejected here exactly as it is
     * everywhere else. The wording is deliberately identical, because a newline is simply allowed rather than being a special case a user needs
     * explaining.
     */
    public static final TextRule NO_INVISIBLE_CHARACTERS_ALLOWING_NEWLINE = new TextRule("noInvisibleCharactersAllowingNewline",
        value -> hasNoInvisibleCharacters(value, true), "cannot contain invisible or text-direction characters.");

    /**
     * A value may not stack more than {@link #MAX_CONSECUTIVE_MARKS} combining marks on one character.
     */
    public static final TextRule NO_STACKED_MARKS = new TextRule("noStackedMarks", TextRules::hasNoStackedMarks,
        "cannot contain more than " + MAX_CONSECUTIVE_MARKS + " combining marks in a row.");

    private TextRules() {

    }

    private static boolean hasNoInvisibleCharacters(final String value, final boolean newlineAllowed) {
        final int[] codePoints = value.codePoints().toArray();
        for (int index = 0; index < codePoints.length; index++) {
            // The exemption is applied HERE rather than inside isInvisible, so isJoinable below keeps asking the strict question: a joiner beside a
            // newline is padding joining nothing, exactly like one beside a space, and must stay rejected on a multi-line field too.
            if (newlineAllowed && codePoints[index] == LINE_FEED) {
                continue;
            }
            final boolean rejected = isJoiner(codePoints[index])
                ? !joinsTwoCharacters(codePoints, index)
                : isInvisible(codePoints[index]);
            if (rejected) {
                return false;
            }
        }

        return true;
    }

    private static boolean isJoiner(final int codePoint) {
        return codePoint == ZERO_WIDTH_JOINER || codePoint == ZERO_WIDTH_NON_JOINER;
    }

    // A joiner is only meaningful BETWEEN two characters - joining the parts of an emoji, or spelling a Persian compound. Anywhere else (leading,
    // trailing, doubled, or beside a space) it is just invisible padding, which is what the rest of this rule exists to reject.
    private static boolean joinsTwoCharacters(final int[] codePoints, final int index) {
        return index > 0
            && index < codePoints.length - 1
            && isJoinable(codePoints[index - 1])
            && isJoinable(codePoints[index + 1]);
    }

    private static boolean isJoinable(final int codePoint) {
        return !Character.isWhitespace(codePoint) && !isJoiner(codePoint) && !isInvisible(codePoint);
    }

    private static boolean isInvisible(final int codePoint) {
        if (isBlankCharacter(codePoint) || isNoncharacter(codePoint)) {
            return true;
        }

        return switch (Character.getType(codePoint)) {
            case Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.CONTROL -> true;
            default -> false;
        };
    }

    // Scanned rather than looked up: the table is seven entries long, and this runs once per code point of every value validated, where a
    // Set<Integer> would box the argument on each call.
    private static boolean isBlankCharacter(final int codePoint) {
        for (final int blank : BLANK_CHARACTERS) {
            if (blank == codePoint) {
                return true;
            }
        }

        return false;
    }

    private static boolean isNoncharacter(final int codePoint) {
        return (codePoint & NONCHARACTER_PLANE_MASK) == NONCHARACTER_PLANE_MASK
            || (codePoint >= NONCHARACTER_BLOCK_START && codePoint <= NONCHARACTER_BLOCK_END);
    }

    private static boolean hasNoStackedMarks(final String value) {
        int run = 0;
        for (final int codePoint : value.codePoints().toArray()) {
            run = isCombiningMark(codePoint) ? (run + 1) : 0;
            if (run > MAX_CONSECUTIVE_MARKS) {
                return false;
            }
        }

        return true;
    }

    private static boolean isCombiningMark(final int codePoint) {
        final int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK;
    }
}
