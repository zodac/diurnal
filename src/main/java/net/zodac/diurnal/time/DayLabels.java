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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DecimalStyle;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * The one place a calendar day is spelled out for the UI. Both dashboard panes that name the selected day - the day-logger panel and the
 * stats-summary card above it - render it through here, so the two headings can never drift into different wordings of the same date.
 *
 * <p>
 * {@link FormatStyle#FULL} (rather than a fixed {@code "EEEE, d MMMM yyyy"} pattern) so the field ORDER, not just the month/day-of-week vocabulary,
 * follows the caller's {@link Locale} - the same distinction {@code .claude/I18N.md}'s Phase 2 draws for every date formatter in the app.
 */
public final class DayLabels {

    private DayLabels() {

    }

    /**
     * Spells a date out in full, in the given locale's own field order (e.g. {@code "Monday, 15 June 2026"} for {@code en-GB}).
     *
     * @param date   the date to spell out
     * @param locale the locale to spell it out in
     * @return the spelled-out label
     */
    public static String spelledOut(final LocalDate date, final Locale locale) {
        // withDecimalStyle is required in addition to withLocale: DateTimeFormatter (unlike NumberFormat) does not
        // switch numbering systems from the locale alone, so the day-of-month/year fields would otherwise stay
        // plain ASCII digits even beside a fully-localised weekday/month name (e.g. Arabic's Eastern Arabic-Indic
        // digits) - see user/Language#localizeNumerals and .claude/I18N.md's Phase 3 follow-up.
        return date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).withDecimalStyle(DecimalStyle.of(locale)));
    }

    /**
     * The seven weekday abbreviations for the dashboard calendar's column header, in Sunday-first order ({@code Sun}, {@code Mon}, ...,
     * {@code Sat} for {@code en-GB}) - the calendar GRID itself stays Sunday-first regardless of locale (a layout concern, not a vocabulary one;
     * see {@code .claude/I18N.md}'s Phase 3), so only the WORDS follow the locale here, never the column order.
     *
     * @param locale the locale to word the weekdays in
     * @return the seven abbreviated weekday names, Sunday first
     */
    public static List<String> weekdayAbbreviations(final Locale locale) {
        return List.of(
            DayOfWeek.SUNDAY.getDisplayName(TextStyle.SHORT, locale),
            DayOfWeek.MONDAY.getDisplayName(TextStyle.SHORT, locale),
            DayOfWeek.TUESDAY.getDisplayName(TextStyle.SHORT, locale),
            DayOfWeek.WEDNESDAY.getDisplayName(TextStyle.SHORT, locale),
            DayOfWeek.THURSDAY.getDisplayName(TextStyle.SHORT, locale),
            DayOfWeek.FRIDAY.getDisplayName(TextStyle.SHORT, locale),
            DayOfWeek.SATURDAY.getDisplayName(TextStyle.SHORT, locale));
    }
}
