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

package net.zodac.diurnal.time;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link Calendars} - which calendar a language expects, and whether the app can render it.
 */
class CalendarsTest {

    @ParameterizedTest
    @CsvSource({
        "en-GB, gregorian",
        "es-ES, gregorian",
        "ar-SA, gregorian",
        "ja-JP, gregorian",
        "he-IL, gregorian",
        "th-TH, buddhist",
        "th, buddhist",
        "fa-IR, persian",
        "ps-AF, persian",
        "uz-Arab-AF, persian",
        "ckb-IR, persian",
    })
    void cldrDefault_answersWhatCldrNames(final String languageTag, final String expected) {
        assertThat(Calendars.cldrDefault(languageTag))
            .as("unexpected CLDR default calendar for '%s'", languageTag)
            .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        // 'uz' and 'ckb' are Gregorian on their own; only the Arabic-script and Iranian forms are Persian, so a match must not be inherited
        // from a shorter prefix that carries a different answer.
        "uz-UZ",
        "ckb-IQ",
        // A longer subtag that merely STARTS with an entry's text is a different language, not a region of it.
        "tha",
        "fao-FO",
    })
    void cldrDefault_matchesWholeSubtagsOnly(final String languageTag) {
        assertThat(Calendars.cldrDefault(languageTag))
            .as("'%s' must not inherit a calendar from a key it only shares a prefix of", languageTag)
            .isEqualTo(Calendars.GREGORIAN);
    }

    @Test
    void cldrDefault_isCaseInsensitive() {
        assertThat(Calendars.cldrDefault("TH-th"))
            .as("BCP 47 says a language tag is case-insensitive, so nothing may depend on the conventional casing")
            .isEqualTo("buddhist");
    }

    @Test
    void isSupported_acceptsOnlyGregorianToday() {
        assertThat(Calendars.isSupported(Calendars.GREGORIAN))
            .as("Gregorian is the one calendar every date renders in")
            .isTrue();
        assertThat(Calendars.isSupported("buddhist"))
            .as("a calendar the app cannot render must not report as supported")
            .isFalse();
    }

    @Test
    void unsupportedCalendar_isEmptyWhenNothingIsDowngraded() {
        assertThat(Calendars.unsupportedCalendar("en-GB"))
            .as("a language whose own calendar is Gregorian is rendered exactly as it should be")
            .isEmpty();
    }

    @Test
    void unsupportedCalendar_namesTheCalendarThatCannotBeRendered() {
        assertThat(Calendars.unsupportedCalendar("th-TH"))
            .as("Thai expects the Buddhist calendar, which the app cannot render - the caller needs to be able to say which one")
            .contains("buddhist");
    }
}
