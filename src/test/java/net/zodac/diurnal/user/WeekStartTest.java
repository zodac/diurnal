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

package net.zodac.diurnal.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class WeekStartTest {

    private static final Locale ENGLISH_GB = Locale.forLanguageTag("en-GB");
    private static final Locale ENGLISH_US = Locale.forLanguageTag("en-US");
    private static final Locale SPANISH = Locale.forLanguageTag("es-ES");
    private static final Locale JAPANESE = Locale.forLanguageTag("ja-JP");

    // ── Constant metadata ───────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({
        "MONDAY, monday, MONDAY",
        "TUESDAY, tuesday, TUESDAY",
        "WEDNESDAY, wednesday, WEDNESDAY",
        "THURSDAY, thursday, THURSDAY",
        "FRIDAY, friday, FRIDAY",
        "SATURDAY, saturday, SATURDAY",
        "SUNDAY, sunday, SUNDAY"})
    void constant_hasExpectedValueAndDay(final WeekStart weekStart, final String value, final DayOfWeek dayOfWeek) {
        assertThat(weekStart.value())
            .as("unexpected value")
            .isEqualTo(value);
        assertThat(weekStart.dayOfWeek())
            .as("unexpected day")
            .isEqualTo(dayOfWeek);
    }

    // ── browserIndex ────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"SUNDAY, 0", "MONDAY, 1", "TUESDAY, 2", "WEDNESDAY, 3", "THURSDAY, 4", "FRIDAY, 5", "SATURDAY, 6"})
    void browserIndex_matchesTheJavascriptDateGetDayNumbering(final WeekStart weekStart, final int expected) {
        assertThat(weekStart.browserIndex())
            .as("the calendar grid offsets by this index, so Sunday must be 0 and Saturday 6 - not java.time's Monday-is-1 numbering")
            .isEqualTo(expected);
    }

    // ── dayName ─────────────────────────────────────────────────────────────

    @Test
    void dayName_isTheDaysFullNameInTheGivenLocale() {
        assertThat(WeekStart.MONDAY.dayName(ENGLISH_GB))
            .as("unexpected English day name")
            .isEqualTo("Monday");
        assertThat(WeekStart.MONDAY.dayName(SPANISH))
            .as("unexpected Spanish day name")
            .isEqualTo("lunes");
        assertThat(WeekStart.MONDAY.dayName(JAPANESE))
            .as("unexpected Japanese day name")
            .isEqualTo("月曜日");
    }

    // ── isValid ─────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"})
    void isValid_offeredValue_returnsTrue(final String value) {
        assertThat(WeekStart.isValid(value))
            .as("expected an offered week-start value to be accepted")
            .isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "MONDAY", "Monday", " monday ", "mon", "automatic", "weekend"})
    void isValid_unknownValue_returnsFalse(final String value) {
        assertThat(WeekStart.isValid(value))
            .as("expected an unrecognised week-start value to be rejected, never coerced")
            .isFalse();
    }

    // ── automatic ───────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"en-GB, MONDAY", "es-ES, MONDAY", "en-US, SUNDAY", "ar-SA, SUNDAY", "ja-JP, SUNDAY"})
    void automatic_followsTheLocalesOwnConvention(final String languageTag, final WeekStart expected) {
        assertThat(WeekStart.automatic(Locale.forLanguageTag(languageTag)))
            .as("an account with no override follows its language's CLDR first day of the week")
            .isEqualTo(expected);
    }

    // ── resolve ─────────────────────────────────────────────────────────────

    @Test
    void resolve_storedValue_overridesTheLocalesConvention() {
        assertThat(WeekStart.resolve("saturday", ENGLISH_GB))
            .as("a stored day is the user's own override and must win over their language's convention")
            .isEqualTo(WeekStart.SATURDAY);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "someday"})
    void resolve_absentOrUnrecognisedValue_fallsBackToTheLocalesConvention(final String storedValue) {
        assertThat(WeekStart.resolve(storedValue, ENGLISH_GB))
            .as("no override (or an unusable stored value) follows the language rather than breaking the calendar")
            .isEqualTo(WeekStart.MONDAY);
        assertThat(WeekStart.resolve(storedValue, ENGLISH_US))
            .as("the fallback is the LOCALE's day, not a fixed one")
            .isEqualTo(WeekStart.SUNDAY);
    }

    // ── choices ─────────────────────────────────────────────────────────────

    @Test
    void choices_offersEveryDayInDeclarationOrder_wordedInTheGivenLocale() {
        final List<String> expectedValues = List.of("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday");
        final List<WeekStart.Choice> choices = WeekStart.choices("monday", ENGLISH_GB);

        assertThat(choices.stream().map(WeekStart.Choice::value).toList())
            .as("the picker offers all seven days, in this enum's declaration order")
            .containsExactlyElementsOf(expectedValues);
        assertThat(choices.getFirst().dayName())
            .as("each option is named from the viewer's own locale")
            .isEqualTo("Monday");
    }

    @Test
    void choices_selectsTheStoredDayOnly() {
        final List<WeekStart.Choice> choices = WeekStart.choices("saturday", ENGLISH_GB);

        assertThat(choices.stream().filter(WeekStart.Choice::selected).map(WeekStart.Choice::value).toList())
            .as("exactly the stored day is pre-selected")
            .containsExactly("saturday");
    }

    @Test
    void choices_noStoredValue_selectsNoDay() {
        final List<WeekStart.Choice> choices = WeekStart.choices(null, ENGLISH_GB);

        assertThat(choices)
            .as("an account following its language selects none of the day options - the picker's own 'Automatic' option carries that state")
            .noneMatch(WeekStart.Choice::selected);
    }
}
