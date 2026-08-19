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

package net.zodac.diurnal.stats;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;
import net.zodac.diurnal.user.Language;
import org.jspecify.annotations.Nullable;

/**
 * Every calendar rule for the frequency chart's window: validating the {@code at} parameter, anchoring a window, wording it, and stepping to the
 * neighbouring one. A window is always carried as the {@link LocalDate} of its FIRST day — the first of the month for {@link FrequencyPeriod#MONTH},
 * the first of January for {@link FrequencyPeriod#YEAR} — so a single date fixes both the window and the arithmetic over it.
 *
 * <p>
 * The wire form of a window (the {@code at} parameter, and the value the chart's navigation buttons post back) is its <em>key</em>: {@code yyyy-MM}
 * for a month, {@code yyyy} for a year. Keys are validated strictly and never coerced: a malformed key is a {@code 400} on both surfaces rather than
 * a silent fallback to the current window, so a bookmarked or scripted request can never quietly return a different window's data than it asked for.
 * The year is bounded to four digits, which is what both key forms can round-trip.
 */
final class FrequencyKeys {

    private static final int MONTHS_PER_YEAR = 12;
    private static final Pattern MONTH_KEY = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final Pattern YEAR_KEY = Pattern.compile("^\\d{4}$");
    private static final DateTimeFormatter MONTH_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ENGLISH);
    // Also YEAR's DISPLAY label (see #label below): four ASCII digits, so it needs no locale - no offered language
    // uses non-Latin digits for a plain year number.
    private static final DateTimeFormatter YEAR_KEY_FMT = DateTimeFormatter.ofPattern("yyyy", Locale.ENGLISH);

    private FrequencyKeys() {

    }

    /**
     * Whether the supplied key is a well-formed window key for the period ({@code yyyy-MM} for a month, {@code yyyy} for a year) that names a real
     * calendar window.
     *
     * @param period the window's period
     * @param key the submitted key (can be {@code null})
     * @return {@code true} when the key can be anchored
     */
    static boolean isValid(final FrequencyPeriod period, final @Nullable String key) {
        if (key == null) {
            return false;
        }

        return switch (period) {
            case MONTH -> MONTH_KEY.matcher(key).matches() && isRealMonth(key);
            case YEAR -> YEAR_KEY.matcher(key).matches();
        };
    }

    /**
     * Anchors a validated key to the first day of the window it names. Only call after {@link #isValid(FrequencyPeriod, String)} has accepted it.
     *
     * @param period the window's period
     * @param key the validated key
     * @return the first day of the named window
     */
    static LocalDate anchor(final FrequencyPeriod period, final String key) {
        return switch (period) {
            case MONTH -> LocalDate.of(year(key), month(key), 1);
            case YEAR -> LocalDate.of(Integer.parseInt(key), 1, 1);
        };
    }

    /**
     * Anchors the window of the given period that CONTAINS the supplied date — the chart's starting window (the date being "today") and the bound
     * every navigation step is clamped against.
     *
     * @param period the window's period
     * @param date any day within the wanted window
     * @return the first day of the window containing {@code date}
     */
    static LocalDate anchorOf(final FrequencyPeriod period, final LocalDate date) {
        return switch (period) {
            case MONTH -> date.withDayOfMonth(1);
            case YEAR -> date.withDayOfYear(1);
        };
    }

    /**
     * The wire key for an anchored window — the inverse of {@link #anchor(FrequencyPeriod, String)}.
     *
     * @param period the window's period
     * @param anchor the first day of the window
     * @return the window's key
     */
    static String key(final FrequencyPeriod period, final LocalDate anchor) {
        return switch (period) {
            case MONTH -> anchor.format(MONTH_KEY_FMT);
            case YEAR -> anchor.format(YEAR_KEY_FMT);
        };
    }

    /**
     * The window's heading, spelled out in full ({@code July 2026} / {@code 2026}).
     *
     * @param period the window's period
     * @param anchor the first day of the window
     * @param language the language to word the label in (a year has no words, so it only affects a month window)
     * @return the window's display label
     */
    static String label(final FrequencyPeriod period, final LocalDate anchor, final Language language) {
        return switch (period) {
            case MONTH -> anchor.format(DateTimeFormatter.ofPattern(language.monthYearPattern(), language.locale()));
            case YEAR -> anchor.format(YEAR_KEY_FMT);
        };
    }

    /**
     * The last day of an anchored window, so a caller can read the window as an inclusive {@code [anchor, end]} date range.
     *
     * @param period the window's period
     * @param anchor the first day of the window
     * @return the last day of the window
     */
    static LocalDate end(final FrequencyPeriod period, final LocalDate anchor) {
        return shift(period, anchor, 1).minusDays(1L);
    }

    /**
     * Steps a window by whole periods — {@code steps} months for a month window, {@code steps} years for a year window. A negative {@code steps} goes
     * backwards.
     *
     * @param period the window's period
     * @param anchor the first day of the window
     * @param steps the number of whole windows to move by
     * @return the first day of the resulting window
     */
    static LocalDate shift(final FrequencyPeriod period, final LocalDate anchor, final int steps) {
        return switch (period) {
            case MONTH -> anchor.plusMonths(steps);
            case YEAR -> anchor.plusYears(steps);
        };
    }

    private static boolean isRealMonth(final String key) {
        final int month = month(key);
        return month >= 1 && month <= MONTHS_PER_YEAR;
    }

    private static int year(final String key) {
        return Integer.parseInt(key.substring(0, 4));
    }

    private static int month(final String key) {
        return Integer.parseInt(key.substring(5, 7));
    }
}
