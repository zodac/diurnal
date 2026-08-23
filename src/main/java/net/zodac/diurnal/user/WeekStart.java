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

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The catalogue of days the dashboard calendar's week can start on - the "Week starts on" setting, and the single source of truth for that picker.
 *
 * <p>
 * Stored in {@code users.week_start} as this enum's {@link #value()}, with {@code NULL} (and a blank submission) meaning "follow the account's
 * language", the same shape {@code users.timezone} uses for "follow the server default": there is no {@code AUTOMATIC} constant and no sentinel
 * string to interpret, so the automatic state has exactly one representation. {@link #resolve(String, Locale)} is the one place that state is
 * turned into a real day, from the CLDR data behind {@link WeekFields#getFirstDayOfWeek()} - Monday for {@code en-GB}/{@code es-ES}, Sunday for
 * {@code en-US}/{@code ar-SA}/{@code ja-JP}.
 *
 * <p>
 * All seven days are offered, not just the three CLDR ever returns (Monday, Saturday, Sunday): the automatic default is what carries the regional
 * convention, and an explicit override exists precisely for the user whose own week does not follow it.
 *
 * <p>
 * Unlike {@link Theme}/{@link Font}/{@link CalendarView}, no constant carries an English label - a weekday's NAME is CLDR data
 * ({@link #dayName(Locale)}), so it is resolved from the viewer's own locale rather than worded here and left untranslated (see
 * {@code .claude/I18N.md}'s "third bucket" rule; a {@code java.time} lookup taking an explicit {@link Locale} is the documented exception to
 * "translated text cannot come from Java").
 */
public enum WeekStart {

    /**
     * The week starts on Monday - the ISO-8601 convention, and the CLDR default for most of Europe.
     */
    MONDAY("monday", DayOfWeek.MONDAY),

    /**
     * The week starts on Tuesday.
     */
    TUESDAY("tuesday", DayOfWeek.TUESDAY),

    /**
     * The week starts on Wednesday.
     */
    WEDNESDAY("wednesday", DayOfWeek.WEDNESDAY),

    /**
     * The week starts on Thursday.
     */
    THURSDAY("thursday", DayOfWeek.THURSDAY),

    /**
     * The week starts on Friday.
     */
    FRIDAY("friday", DayOfWeek.FRIDAY),

    /**
     * The week starts on Saturday - the CLDR default across much of the Middle East.
     */
    SATURDAY("saturday", DayOfWeek.SATURDAY),

    /**
     * The week starts on Sunday - the CLDR default for {@code en-US}/{@code ja-JP}, and the fixed order every calendar in this app used before the
     * setting existed.
     */
    SUNDAY("sunday", DayOfWeek.SUNDAY);

    private final String value;
    private final DayOfWeek dayOfWeek;

    WeekStart(final String value, final DayOfWeek dayOfWeek) {
        this.value = value;
        this.dayOfWeek = dayOfWeek;
    }

    /**
     * The stable identifier: the option value posted by the form and persisted for the setting.
     *
     * @return the week-start value
     */
    public String value() {
        return value;
    }

    /**
     * The day itself, for the calendar's column order.
     *
     * @return the first day of the week
     */
    public DayOfWeek dayOfWeek() {
        return dayOfWeek;
    }

    /**
     * The day's own name in the given locale, spelled out in full (e.g. {@code "Monday"} for {@code en-GB}, {@code "الاثنين"} for {@code ar-SA}) -
     * the picker's option label, and the day named inside the automatic option's own label.
     *
     * @param locale the locale to name the day in
     * @return the day's full CLDR name
     */
    public String dayName(final Locale locale) {
        return dayOfWeek.getDisplayName(TextStyle.FULL, locale);
    }

    /**
     * This day as a JavaScript {@code Date#getDay()} index ({@code 0} = Sunday ... {@code 6} = Saturday), which is the numbering the dashboard
     * calendar engine offsets its 42-cell grid by. {@link DayOfWeek#getValue()} counts Monday as {@code 1} through Sunday as {@code 7}, so the
     * modulo is what maps Sunday back onto {@code 0}.
     *
     * @return the browser's index for this day
     */
    public int browserIndex() {
        return dayOfWeek.getValue() % DayOfWeek.values().length;
    }

    /**
     * A single day option in the "Week starts on" picker: the form {@link #value}, the day's own name in the viewer's language, and whether it is
     * the account's current choice. The picker's remaining option - "Automatic" - is not one of these: its label is app chrome rather than CLDR
     * data, so it is worded by {@code AppMessages#weekStartAutomatic} at the template's own render site (see {@code .claude/I18N.md}), around the
     * day name {@link #automatic(Locale)} resolves.
     *
     * @param value   the form value to post for this day
     * @param dayName the day's full name in the viewer's language
     * @param selected whether this is the account's stored choice
     */
    public record Choice(String value, String dayName, boolean selected) {

    }

    /**
     * Builds the seven day options of the "Week starts on" picker, in this enum's own declaration order (Monday through Sunday), with the option
     * matching the account's stored value pre-selected. An account following its language has no day selected here - the picker's separate
     * "Automatic" option is what carries that state.
     *
     * @param storedValue the account's stored preference ({@code null} or blank = following the language)
     * @param locale      the locale to name each day in
     * @return the seven day options
     */
    public static List<Choice> choices(final @Nullable String storedValue, final Locale locale) {
        return Arrays.stream(values())
            .map(option -> new Choice(option.value, option.dayName(locale), option.value.equals(storedValue)))
            .toList();
    }

    /**
     * Whether the submitted value matches one of the offered options. Submissions with an unrecognised value are rejected by the caller
     * ({@code ProfileService}) rather than coerced; a blank submission is the explicit "follow the language" reset instead.
     *
     * @param value the submitted value (can be {@code null})
     * @return {@code true} when the value is one of the offered options
     */
    public static boolean isValid(final @Nullable String value) {
        return Arrays.stream(values()).anyMatch(option -> option.value.equals(value));
    }

    /**
     * The day the given locale's own convention starts its week on - what an account that has never set the preference (or has reset it) follows.
     *
     * @param locale the account's language as a locale
     * @return the locale's first day of the week
     */
    public static WeekStart automatic(final Locale locale) {
        final DayOfWeek firstDay = WeekFields.of(locale).getFirstDayOfWeek();
        return Arrays.stream(values()).filter(option -> option.dayOfWeek == firstDay).findFirst().orElse(MONDAY);
    }

    /**
     * Resolves a stored preference into the day the calendar's week actually starts on: the matching constant for a stored value, or the locale's
     * own convention ({@link #automatic(Locale)}) when the account has no override.
     *
     * <p>
     * An unrecognised stored value falls back to the automatic day rather than throwing, the same defensive shape {@code AppClock#zoneFor} applies
     * to a stored timezone - a value that could only get there by a hand-edited row must not break the dashboard.
     *
     * @param storedValue the stored preference ({@code null} or blank = follow the locale)
     * @param locale      the account's language as a locale
     * @return the resolved first day of the week
     */
    public static WeekStart resolve(final @Nullable String storedValue, final Locale locale) {
        return Arrays.stream(values())
            .filter(option -> option.value.equals(storedValue))
            .findFirst()
            .orElseGet(() -> automatic(locale));
    }
}
