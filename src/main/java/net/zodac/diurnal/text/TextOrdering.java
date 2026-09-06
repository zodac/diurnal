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

import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;

/**
 * How a list of user-typed names is ordered for a reader - the one place the app decides what "alphabetical" means.
 *
 * <p>
 * <strong>Two rules, and neither is the JVM's default.</strong> A plain {@link String#compareTo(String)} is code-point order, which puts every
 * uppercase name before every lowercase one and every accented or non-Latin name after every plain-ASCII one; and the database cannot be relied on
 * either, since {@code ORDER BY name} inherits whatever collation the container was initialised with (the deployed PostgreSQL image reports
 * {@code en_US.utf8} but is built on musl, whose stub locale support sorts {@code Zebra} before {@code apple}). So ordering is decided here, in the
 * viewing user's own language, over an already-fetched list.
 *
 * <ol>
 * <li><strong>Letters collate per locale</strong>, via {@link Collator} - Swedish sorts {@code a-ring} after {@code z}, Czech treats {@code ch} as
 * one letter after {@code h}, Estonian puts {@code z} between {@code s} and {@code t}. Measured against {@code ICU4J} across five scripts and found
 * identical, which is why this needs no third-party collator.</li>
 * <li><strong>Digits compare as NUMBERS</strong>, so {@code "Run 2"} precedes {@code "Run 10"} rather than following it. No collator does this on its
 * own - {@code ICU4J} offers it as a switch and the JDK offers nothing - and it is the difference a user is most likely to notice, because naming a
 * series of things is what people do. Deliberately hand-rolled rather than bought: it is the only part of the app's sorting the JDK cannot supply,
 * and it is worth far less than the 14 MB the switch would cost.</li>
 * </ol>
 *
 * <p>
 * The digit rule reads any script's digits, not just ASCII, so an Arabic-Indic {@code "٢"} orders against {@code "١٠"} exactly as {@code "2"} orders
 * against {@code "10"} - and against them, since both are read as their numeric value.
 */
public final class TextOrdering {

    private static final int RADIX = 10;

    private TextOrdering() {

    }

    /**
     * Orders user-typed names for a reader of the given locale: letters collated for that locale, runs of digits compared as numbers.
     *
     * <p>
     * The returned comparator holds a {@link Collator}, which is not thread-safe - build one per request rather than caching one in a field, the way
     * every caller does today.
     *
     * @param locale the viewing user's locale
     * @return the comparator
     */
    public static Comparator<String> byName(final Locale locale) {
        final Collator collator = Collator.getInstance(locale);
        return (left, right) -> compare(collator, left, right);
    }

    private static int compare(final Collator collator, final String left, final String right) {
        final int leftLength = left.length();
        final int rightLength = right.length();
        int leftAt = 0;
        int rightAt = 0;

        while (leftAt < leftLength && rightAt < rightLength) {
            final int leftEnd = runEnd(left, leftAt);
            final int rightEnd = runEnd(right, rightAt);
            final String leftRun = left.substring(leftAt, leftEnd);
            final String rightRun = right.substring(rightAt, rightEnd);

            // Two digit runs are compared as numbers; anything else - including a digit run meeting a letter run - is the collator's business, so a
            // name that merely starts with a digit still sorts against a name that does not exactly as that language expects.
            final int result = isDigit(left, leftAt) && isDigit(right, rightAt)
                ? compareAsNumbers(leftRun, rightRun)
                : collator.compare(leftRun, rightRun);
            if (result != 0) {
                return result;
            }

            leftAt = leftEnd;
            rightAt = rightEnd;
        }

        // Whatever is left over, which is how a name that PREFIXES another sorts before it.
        return collator.compare(left.substring(leftAt), right.substring(rightAt));
    }

    private static int compareAsNumbers(final String left, final String right) {
        final String leftDigits = withoutLeadingZeros(left);
        final String rightDigits = withoutLeadingZeros(right);
        final int leftDigitsLength = leftDigits.length();

        if (leftDigitsLength != rightDigits.length()) {
            return Integer.compare(leftDigitsLength, rightDigits.length());
        }

        // Digit by digit rather than by parsing: a name can hold more digits than any numeric type carries, and a value is read with Character#digit
        // so a run written in another script's digits compares by what it MEANS rather than by its code points.
        for (int i = 0; i < leftDigitsLength; i++) {
            final int difference = Character.digit(leftDigits.charAt(i), RADIX) - Character.digit(rightDigits.charAt(i), RADIX);
            if (difference != 0) {
                return difference;
            }
        }

        // Equal in value, so the runs differ only in how they were WRITTEN ("7" against "007"). Settled on the raw length rather than left to tie,
        // because a tie here falls through to whatever order the list arrived in - which is the database's, and so not an order at all.
        return Integer.compare(left.length(), right.length());
    }

    // An all-zero run yields the empty string, deliberately: it has no significant digits, and a length of zero already sorts it below every run
    // that has one. Keeping a token "0" would need arithmetic here for no behavioural difference.
    private static String withoutLeadingZeros(final String digits) {
        final int digitsLength = digits.length();
        int firstSignificant = 0;
        while (firstSignificant < digitsLength && Character.digit(digits.charAt(firstSignificant), RADIX) == 0) {
            firstSignificant++;
        }
        return digits.substring(firstSignificant);
    }

    private static int runEnd(final String value, final int from) {
        final boolean digits = isDigit(value, from);
        int at = from;
        while (at < value.length() && isDigit(value, at) == digits) {
            at += Character.charCount(value.codePointAt(at));
        }
        return at;
    }

    private static boolean isDigit(final String value, final int at) {
        return Character.isDigit(value.codePointAt(at));
    }
}
