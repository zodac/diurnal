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

package net.zodac.diurnal.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArabicPluralTest {

    @Test
    void arabicPluralCategory_zero() {
        assertThat(ArabicPlural.arabicPluralCategory(0))
            .as("CLDR ar: exactly zero")
            .isEqualTo("zero");
    }

    @Test
    void arabicPluralCategory_one() {
        assertThat(ArabicPlural.arabicPluralCategory(1))
            .as("CLDR ar: exactly one")
            .isEqualTo("one");
    }

    @Test
    void arabicPluralCategory_two() {
        assertThat(ArabicPlural.arabicPluralCategory(2))
            .as("CLDR ar: exactly two (dual)")
            .isEqualTo("two");
    }

    @Test
    void arabicPluralCategory_few_lowerAndUpperBound() {
        assertThat(ArabicPlural.arabicPluralCategory(3))
            .as("CLDR ar: few starts at 3")
            .isEqualTo("few");
        assertThat(ArabicPlural.arabicPluralCategory(10))
            .as("CLDR ar: few ends at 10")
            .isEqualTo("few");
    }

    @Test
    void arabicPluralCategory_many_lowerAndUpperBound() {
        assertThat(ArabicPlural.arabicPluralCategory(11))
            .as("CLDR ar: many starts at 11")
            .isEqualTo("many");
        assertThat(ArabicPlural.arabicPluralCategory(99))
            .as("CLDR ar: many ends at 99")
            .isEqualTo("many");
    }

    @Test
    void arabicPluralCategory_other_roundHundred() {
        assertThat(ArabicPlural.arabicPluralCategory(100))
            .as("CLDR ar: a count ending 00 falls back to other, not zero")
            .isEqualTo("other");
    }

    @Test
    void arabicPluralCategory_other_hundredAndOneOrTwo() {
        assertThat(ArabicPlural.arabicPluralCategory(101))
            .as("CLDR ar: a count ending 01 falls back to other, not one")
            .isEqualTo("other");
        assertThat(ArabicPlural.arabicPluralCategory(102))
            .as("CLDR ar: a count ending 02 falls back to other, not two")
            .isEqualTo("other");
    }

    @Test
    void arabicPluralCategory_few_afterOneHundred() {
        assertThat(ArabicPlural.arabicPluralCategory(103))
            .as("CLDR ar: the last two digits (03) still drive the category past 100")
            .isEqualTo("few");
    }

    @Test
    void arabicPluralCategory_many_afterOneHundred() {
        assertThat(ArabicPlural.arabicPluralCategory(111))
            .as("CLDR ar: the last two digits (11) still drive the category past 100")
            .isEqualTo("many");
    }

    @Test
    void arabicPluralCategory_other_roundTwoHundred() {
        assertThat(ArabicPlural.arabicPluralCategory(200))
            .as("CLDR ar: every round hundred is other, not just 100")
            .isEqualTo("other");
    }

    @Test
    void arabicPluralCategory_intOverload_matchesLongOverload() {
        assertThat(ArabicPlural.arabicPluralCategory(3))
            .as("the int overload used by @Message(int) parameters must agree with the long overload")
            .isEqualTo(ArabicPlural.arabicPluralCategory(3L));
    }
}
