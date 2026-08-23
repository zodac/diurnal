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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DayLabelsTest {

    private static final Locale ENGLISH_GB = Locale.forLanguageTag("en-GB");
    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");

    // ── spelledOut ──────────────────────────────────────────────────────────

    @Test
    void spelledOut_usesTheLocalesOwnFieldOrder() {
        final LocalDate date = LocalDate.of(2026, 6, 15);

        assertThat(DayLabels.spelledOut(date, ENGLISH_GB))
            .as("unexpected English label")
            .isEqualTo("Monday, 15 June 2026");
        assertThat(DayLabels.spelledOut(date, SPANISH))
            .as("unexpected Spanish label")
            .isEqualTo("lunes, 15 de junio de 2026");
    }

    // ── weekdayAbbreviations ────────────────────────────────────────────────

    @Test
    void weekdayAbbreviations_startOnMonday_rotatesSundayToTheEnd() {
        final List<String> expected = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

        assertThat(DayLabels.weekdayAbbreviations(ENGLISH_GB, DayOfWeek.MONDAY))
            .as("unexpected Monday-first column header")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void weekdayAbbreviations_startOnSunday_leadsWithSunday() {
        final List<String> expected = List.of("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat");

        assertThat(DayLabels.weekdayAbbreviations(ENGLISH_GB, DayOfWeek.SUNDAY))
            .as("unexpected Sunday-first column header")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void weekdayAbbreviations_startMidWeek_wrapsAroundTheWeek() {
        final List<String> expected = List.of("Wed", "Thu", "Fri", "Sat", "Sun", "Mon", "Tue");

        assertThat(DayLabels.weekdayAbbreviations(ENGLISH_GB, DayOfWeek.WEDNESDAY))
            .as("every day is offered as a week start, so a mid-week start must wrap rather than run off the end")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void weekdayAbbreviations_wordsTheDaysInTheGivenLocale() {
        final List<String> expected = List.of("lun", "mar", "mié", "jue", "vie", "sáb", "dom");

        assertThat(DayLabels.weekdayAbbreviations(SPANISH, DayOfWeek.MONDAY))
            .as("the WORDS follow the locale, independently of the column order")
            .containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @EnumSource(DayOfWeek.class)
    void weekdayAbbreviations_anyStartDay_namesAllSevenDaysOnce(final DayOfWeek firstDay) {
        final List<String> labels = DayLabels.weekdayAbbreviations(ENGLISH_GB, firstDay);

        assertThat(labels)
            .as("the calendar's header has exactly seven columns, each a distinct day, whatever the week starts on")
            .hasSize(7)
            .doesNotHaveDuplicates();
        assertThat(labels.getFirst())
            .as("the header must lead with the day the week starts on")
            .isEqualTo(firstDay.getDisplayName(java.time.format.TextStyle.SHORT, ENGLISH_GB));
    }
}
