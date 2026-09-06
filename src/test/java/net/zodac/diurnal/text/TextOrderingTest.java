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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link TextOrdering} - locale-collated letters, and runs of digits compared as numbers.
 */
class TextOrderingTest {

    private static final Locale UK = Locale.forLanguageTag("en-GB");

    private static List<String> sorted(final Locale locale, final String... names) {
        return Stream.of(names).sorted(TextOrdering.byName(locale)).toList();
    }

    @Test
    void byName_comparesRunsOfDigitsAsNumbers() {
        assertThat(sorted(UK, "Run 10", "Run 2", "Run 1", "Run 20"))
            .as("a number in a name must order by its value, not a digit at a time")
            .containsExactly("Run 1", "Run 2", "Run 10", "Run 20");
    }

    @Test
    void byName_comparesNumbersLongerThanAnyNumericType() {
        assertThat(sorted(UK, "x99999999999999999999999", "x100000000000000000000000"))
            .as("a name can hold more digits than a long carries, so the comparison must not parse one")
            .containsExactly("x99999999999999999999999", "x100000000000000000000000");
    }

    @Test
    void byName_readsAnyScriptsDigitsAsTheirValue() {
        // Eastern Arabic-Indic two and ten: ordering by code point would put the two-character run first regardless of value.
        assertThat(sorted(Locale.forLanguageTag("ar-SA"), "نشاط ١٠", "نشاط ٢"))
            .as("a digit is read by what it means, so another script's numerals order like Latin ones")
            .containsExactly("نشاط ٢", "نشاط ١٠");
    }

    @Test
    void byName_ordersLeadingZeroesBesideTheirValue() {
        assertThat(sorted(UK, "Set 10", "Set 007", "Set 7"))
            .as("leading zeroes change how a number is written, not what it is worth")
            .containsExactly("Set 7", "Set 007", "Set 10");
    }

    @Test
    void byName_ordersAnAllZeroRunBelowEveryOtherNumber() {
        assertThat(sorted(UK, "Set 1", "Set 000", "Set 0"))
            .as("a run with no significant digits is worth less than one that has any, however it was written")
            .containsExactly("Set 0", "Set 000", "Set 1");
    }

    @Test
    void byName_fallsBackToTheCollatorWhenOneNamePrefixesTheOther() {
        final Comparator<String> byName = TextOrdering.byName(UK);

        // Asserted in BOTH directions: each exhausts a different side of the walk, and only one of them would catch a bound that reads one
        // character too far.
        assertThat(byName.compare("Run 2", "Run 2 evening"))
            .as("the shorter name comes first once every shared run has compared equal")
            .isNegative();
        assertThat(byName.compare("Run 2 evening", "Run 2"))
            .as("and the mirrored comparison must agree")
            .isPositive();
    }

    @Test
    void byName_doesNotTreatDigitRunAsEqualToLetterRun() {
        assertThat(sorted(UK, "beta", "2nd"))
            .as("a digit run meeting a letter run is the collator's business, not the numeric rule's")
            .containsExactly("2nd", "beta");
    }

    @Test
    void byName_collatesLettersRatherThanComparingCodePoints() {
        assertThat(sorted(UK, "Zebra", "apple"))
            .as("code-point order would put every uppercase name before every lowercase one")
            .containsExactly("apple", "Zebra");
    }

    @ParameterizedTest
    @CsvSource({
        // The cases that tell a locale-aware collator from a naive one; each was verified identical to ICU4J's answer.
        "sv-SE, z,  ä,  z,  ä",
        "de-DE, z,  ä,  ä,  z",
        "et-EE, t,  z,  z,  t",
        "tr-TR, i,  ı,  ı,  i",
    })
    void byName_ordersAccordingToTheViewersOwnLanguage(final String tag, final String first, final String second,
        final String expectedFirst, final String expectedSecond) {
        assertThat(sorted(Locale.forLanguageTag(tag), first, second))
            .as("'%s' orders these two letters its own way", tag)
            .containsExactly(expectedFirst, expectedSecond);
    }

    @Test
    void byName_isReusableAcrossComparisons() {
        final Comparator<String> byName = TextOrdering.byName(UK);

        assertThat(byName.compare("Run 2", "Run 10"))
            .as("one comparator serves a whole list, so it must hold no per-comparison state")
            .isNegative();
        assertThat(byName.compare("Run 10", "Run 2"))
            .as("and must answer the mirrored comparison consistently")
            .isPositive();
    }
}
