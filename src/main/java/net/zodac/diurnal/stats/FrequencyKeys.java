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
 * Every calendar rule for the frequency chart's window: validating the {@code at} parameter, anchoring a window, wording it, and stepping to its
 * neighbour. A window is always carried as the {@link LocalDate} of its FIRST day — the first of the month for {@link FrequencyPeriod#MONTH}, the
 * first of January for {@link FrequencyPeriod#YEAR} and {@link FrequencyPeriod#ALL} — so a single date fixes both the window and the arithmetic
 * over it.
 *
 * <p>
 * The wire form of a window (the {@code at} parameter, and the value the chart's navigation buttons post back) is its <em>key</em>: {@code yyyy-MM}
 * for a month, {@code yyyy} for a year, the fixed {@code all} for the all-time window (naming no calendar unit, since there's only ever one). Keys
 * are validated strictly and never coerced: a malformed key is a {@code 400} on both surfaces rather than a silent fallback to the current window,
 * so a bookmarked or scripted request can never quietly get a different window's data than it asked for. The year is bounded to four digits, which
 * both dated key forms can round-trip.
 *
 * <p>
 * The all-time window is the one whose bounds the key does NOT fix: it starts at the account's earliest logged day and runs to the end of the current
 * year, so anchoring it takes the data rather than the key alone. Everything downstream still sees a plain anchored window.
 */
final class FrequencyKeys {

    private static final int MONTHS_PER_YEAR = 12;
    private static final String ALL_KEY = "all";
    private static final String YEAR_PATTERN = "yyyy";
    private static final Pattern MONTH_KEY = Pattern.compile("^\\d{4}-\\d{2}$");
    private static final Pattern YEAR_KEY = Pattern.compile("^\\d{4}$");
    private static final DateTimeFormatter MONTH_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ENGLISH);
    // The wire key ONLY (see #key below) - fixed ASCII digits and Locale.ENGLISH deliberately, since this round-trips
    // through URLs/params and must parse identically regardless of viewer language. NOT used for the YEAR display
    // label any more (see #label below) - Arabic's Eastern Arabic-Indic digits proved that claim wrong; #label
    // builds its own locale-decimal-styled formatter for that case instead.
    private static final DateTimeFormatter YEAR_KEY_FMT = DateTimeFormatter.ofPattern(YEAR_PATTERN, Locale.ENGLISH);

    private FrequencyKeys() {

    }

    /**
     * Whether the supplied key is a well-formed window key for the period ({@code yyyy-MM} for a month, {@code yyyy} for a year, the fixed
     * {@code all} for the all-time window) that names a real calendar window.
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
            case ALL -> ALL_KEY.equals(key);
        };
    }

    /**
     * Anchors a validated key to the first day of the window it names. Only call after {@link #isValid(FrequencyPeriod, String)} has accepted it.
     *
     * <p>
     * The all-time key names the one window there is, so that window is anchored by the DATA rather than by the key — identically to
     * {@link #defaultAnchor(FrequencyPeriod, LocalDate, LocalDate)}, which is why asking for it explicitly and not asking at all return the same
     * window.
     *
     * @param period the window's period
     * @param key the validated key
     * @param today the current day, which bounds the all-time window (ignored by the dated periods)
     * @param earliest the earliest day any charted subject has an entry, which starts the all-time window, or {@code null} when none of them has any
     * @return the first day of the named window
     */
    static LocalDate anchor(final FrequencyPeriod period, final String key, final LocalDate today, final @Nullable LocalDate earliest) {
        return switch (period) {
            case MONTH -> LocalDate.of(year(key), month(key), 1);
            case YEAR -> LocalDate.of(Integer.parseInt(key), 1, 1);
            case ALL -> allTimeAnchor(today, earliest);
        };
    }

    /**
     * The window a period opens on when the request named none: the one containing today for a month or a year, and the whole of the account's
     * history for the all-time period.
     *
     * @param period the window's period
     * @param today the current day
     * @param earliest the earliest day any charted subject has an entry, or {@code null} when none of them has any
     * @return the first day of the period's default window
     */
    static LocalDate defaultAnchor(final FrequencyPeriod period, final LocalDate today, final @Nullable LocalDate earliest) {
        return switch (period) {
            case MONTH, YEAR -> anchorOf(period, today);
            case ALL -> allTimeAnchor(today, earliest);
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
            case YEAR, ALL -> date.withDayOfYear(1);
        };
    }

    /**
     * The wire key for an anchored window — the inverse of {@link #anchor(FrequencyPeriod, String, LocalDate, LocalDate)}.
     *
     * @param period the window's period
     * @param anchor the first day of the window
     * @return the window's key
     */
    static String key(final FrequencyPeriod period, final LocalDate anchor) {
        return switch (period) {
            case MONTH -> anchor.format(MONTH_KEY_FMT);
            case YEAR -> anchor.format(YEAR_KEY_FMT);
            case ALL -> ALL_KEY;
        };
    }

    /**
     * The window's heading, spelled out in full ({@code July 2026} / {@code 2026} / {@code 2024 – 2026}). Unlike
     * {@link #key(FrequencyPeriod, LocalDate)}, this is user-visible, so - despite a year having no WORDS to localise - its digits still need this
     * language's own glyphs (e.g. Eastern Arabic-Indic for Arabic); {@link #YEAR_KEY_FMT} cannot be reused here for that reason (it is fixed-ASCII,
     * deliberately, for the wire key).
     *
     * <p>
     * The all-time window is worded as the span of years it draws, and joined with this language's own
     * {@link Language#dateRangeSeparator() range separator} rather than an en dash everywhere (Japanese writes a range with a wave dash). A window
     * that covers a single year - a new account's - reads as that year alone rather than as a range with the same year on both sides.
     *
     * @param period the window's period
     * @param anchor the first day of the window
     * @param today the current day, which ends the all-time window (ignored by the dated periods)
     * @param language the language to word the label in
     * @return the window's display label
     */
    static String label(final FrequencyPeriod period, final LocalDate anchor, final LocalDate today, final Language language) {
        return switch (period) {
            case MONTH -> anchor.format(language.localizeNumerals(DateTimeFormatter.ofPattern(language.monthYearPattern(), language.locale())));
            case YEAR -> yearLabel(anchor, language);
            case ALL -> allTimeLabel(anchor, today, language);
        };
    }

    /**
     * One year spelled out in this language's own digits — the year window's heading, and every slot of the all-time window's axis and hover label.
     *
     * @param anchor a day within the year to word
     * @param language the language whose digits to word it in
     * @return the year's display label
     */
    static String yearLabel(final LocalDate anchor, final Language language) {
        return anchor.format(language.localizeNumerals(DateTimeFormatter.ofPattern(YEAR_PATTERN, language.locale())));
    }

    /**
     * The last day of an anchored window, so a caller can read the window as an inclusive {@code [anchor, end]} date range. The all-time window ends
     * with the current year: no period draws a window the chart's navigation could not reach, and a future-dated entry belongs to none of them.
     *
     * @param period the window's period
     * @param anchor the first day of the window
     * @param today the current day, which ends the all-time window (ignored by the dated periods)
     * @return the last day of the window
     */
    static LocalDate end(final FrequencyPeriod period, final LocalDate anchor, final LocalDate today) {
        return switch (period) {
            case MONTH, YEAR -> shift(period, anchor, 1).minusDays(1L);
            case ALL -> anchorOf(FrequencyPeriod.YEAR, today).plusYears(1L).minusDays(1L);
        };
    }

    /**
     * Steps a window by whole periods — {@code steps} months for a month window, {@code steps} years for a year window. A negative {@code steps} goes
     * backwards. Stepping the all-time window is a no-op: it spans everything there is, so it has no neighbour to land on and the chart never offers
     * the step (see {@link FrequencyPeriod#steppable()}).
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
            case ALL -> anchor;
        };
    }

    // The window starts at the first year holding an entry. An account with nothing logged - and one whose only entries are still in the FUTURE,
    // which no window of any period draws - starts at the current year instead, so the chart always has at least its own year to draw.
    private static LocalDate allTimeAnchor(final LocalDate today, final @Nullable LocalDate earliest) {
        return anchorOf(FrequencyPeriod.ALL, earliest == null || earliest.isAfter(today) ? today : earliest);
    }

    private static String allTimeLabel(final LocalDate anchor, final LocalDate today, final Language language) {
        final String first = yearLabel(anchor, language);
        final String last = yearLabel(anchorOf(FrequencyPeriod.ALL, today), language);
        return first.equals(last) ? first : first + language.dateRangeSeparator() + last;
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
