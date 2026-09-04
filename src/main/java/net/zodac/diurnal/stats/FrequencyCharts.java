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
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.user.Language;
import org.jspecify.annotations.Nullable;

/**
 * Assembles a {@link FrequencyChart} from already-aggregated windows of counts — all the chart's branching (bar heights, empty slots, which
 * navigation steps are offered) in one pure, database-free place, so it is unit-tested rather than only exercised through a rendered page.
 *
 * <p>
 * The counts arrive per charted action, keyed by <em>slot</em>: the day-of-month for a {@link FrequencyPeriod#MONTH} window, the month-of-year for a
 * {@link FrequencyPeriod#YEAR} one. That keeps the query projections ({@code DailyActionTotal} / {@code MonthlyActionTotal}) on the service's side of
 * the seam and leaves this class holding nothing but calendar arithmetic.
 */
final class FrequencyCharts {

    /**
     * The most actions that may be charted together. Three is what stays legible: a month window already draws 31 columns, so each extra action thins
     * every bar, and the column's hover bubble has to name each one on its own line.
     */
    static final int MAX_SERIES = 3;

    private static final int MONTHS_PER_YEAR = 12;
    private static final int MIN_VISIBLE_PERCENT = 3;
    private static final double PERCENT_SCALE = 100.0;
    private static final int EDGE_SLOTS = 4;

    private FrequencyCharts() {

    }

    /**
     * Builds the chart for one or more actions over one window.
     *
     * @param charted the subjects to chart, in legend order; the first is the one the graph was opened from and is not removable
     * @param period the window's period
     * @param anchor the first day of the window
     * @param countsByAction each charted subject's counts, keyed by day-of-month (a month window) or month-of-year (a year window); an absent
     *     subject, or an absent slot within one, is empty
     * @param today the current day, which bounds how far forward the chart may be stepped
     * @param earliest the earliest day any charted subject has an entry, which bounds how far back it may be stepped, or {@code null} when none
     *     of them has any
     * @param language the language to word the chart's month/year labels in
     * @return the assembled chart
     */
    static FrequencyChart build(final List<StatSubject> charted, final FrequencyPeriod period, final LocalDate anchor,
        final Map<UUID, Map<Integer, Long>> countsByAction, final LocalDate today, final @Nullable LocalDate earliest, final Language language) {
        final int slots = slotCount(period, anchor);

        long total = 0L;
        long peak = 0L;
        for (final StatSubject subject : charted) {
            final Map<Integer, Long> counts = countsByAction.getOrDefault(subject.id(), Map.of());
            for (int slot = 1; slot <= slots; slot++) {
                final long count = counts.getOrDefault(slot, 0L);
                total += count;
                peak = Math.max(peak, count);
            }
        }

        final List<FrequencySlot> columns = new ArrayList<>(slots);
        for (int slot = 1; slot <= slots; slot++) {
            final List<FrequencyBar> bars = new ArrayList<>(charted.size());
            for (final StatSubject subject : charted) {
                final long count = countsByAction.getOrDefault(subject.id(), Map.of()).getOrDefault(slot, 0L);
                bars.add(new FrequencyBar(subject.id(), subject.name(), subject.colour(), count, heightPercent(count, peak)));
            }
            columns.add(new FrequencySlot(shortLabel(period, anchor, slot, language), fullLabel(period, anchor, slot, language),
                tipAlign(slot, slots), List.copyOf(bars)));
        }

        final int chartedCount = charted.size();
        final List<FrequencySeries> series = new ArrayList<>(chartedCount);
        for (int index = 0; index < chartedCount; index++) {
            final StatSubject subject = charted.get(index);
            series.add(new FrequencySeries(subject.id(), subject.name(), subject.colour(),
                seriesTotal(countsByAction.getOrDefault(subject.id(), Map.of()), slots), index > 0));
        }

        final LocalDate previous = FrequencyKeys.shift(period, anchor, -1);
        final LocalDate next = FrequencyKeys.shift(period, anchor, 1);
        return new FrequencyChart(
            period,
            FrequencyKeys.key(period, anchor),
            FrequencyKeys.label(period, anchor, language),
            List.copyOf(series),
            List.copyOf(columns),
            total,
            peak,
            hasEarlier(period, previous, earliest),
            FrequencyKeys.key(period, previous),
            !next.isAfter(FrequencyKeys.anchorOf(period, today)),
            FrequencyKeys.key(period, next));
    }

    /**
     * A bar's drawn height, as a percentage of the chart's tallest bar. An empty slot draws nothing at all; every logged slot is floored at a few
     * percent so a single entry alongside a very tall peak is still a visible bar rather than rounding away to an invisible sliver.
     *
     * @param count the bar's logged count
     * @param peak the chart's tallest count
     * @return the bar height as a percentage
     */
    static int heightPercent(final long count, final long peak) {
        if (count <= 0L || peak <= 0L) {
            return 0;
        }
        return Math.max(MIN_VISIBLE_PERCENT, (int) Math.round(count * PERCENT_SCALE / peak));
    }

    /**
     * Which side a column's hover bubble is anchored to. A column within the first or last few slots grows its bubble INWARD, so a wide value on a
     * narrow chart can never push the panel (or the page behind it) sideways — the same reasoning as the calendar toolbar's edge buttons.
     *
     * @param slot the 1-based slot
     * @param slots the number of slots in the window
     * @return the {@code partials/tooltip} {@code align} value
     */
    static String tipAlign(final int slot, final int slots) {
        if (slot <= EDGE_SLOTS) {
            return "left";
        }
        return slot > slots - EDGE_SLOTS ? "right" : "center";
    }

    private static long seriesTotal(final Map<Integer, Long> counts, final int slots) {
        long total = 0L;
        for (int slot = 1; slot <= slots; slot++) {
            total += counts.getOrDefault(slot, 0L);
        }
        return total;
    }

    private static boolean hasEarlier(final FrequencyPeriod period, final LocalDate previous, final @Nullable LocalDate earliest) {
        return earliest != null && !previous.isBefore(FrequencyKeys.anchorOf(period, earliest));
    }

    private static int slotCount(final FrequencyPeriod period, final LocalDate anchor) {
        return switch (period) {
            case MONTH -> anchor.lengthOfMonth();
            case YEAR -> MONTHS_PER_YEAR;
        };
    }

    private static String shortLabel(final FrequencyPeriod period, final LocalDate anchor, final int slot, final Language language) {
        return switch (period) {
            case MONTH -> String.valueOf(slot);
            case YEAR -> anchor.withMonth(slot).getMonth().getDisplayName(TextStyle.SHORT, language.locale());
        };
    }

    private static String fullLabel(final FrequencyPeriod period, final LocalDate anchor, final int slot, final Language language) {
        return switch (period) {
            case MONTH -> anchor.withDayOfMonth(slot)
                .format(language.localizeNumerals(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(language.locale())));
            case YEAR -> anchor.withMonth(slot)
                .format(language.localizeNumerals(DateTimeFormatter.ofPattern(language.monthYearPattern(), language.locale())));
        };
    }

}
