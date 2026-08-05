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

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import java.time.LocalDate;
import java.util.List;
import net.zodac.diurnal.time.DayLabels;
import net.zodac.diurnal.user.User;

/**
 * Binds the data {@code partials/stats-summary.html} needs onto a template instance. The dashboard's summary card is rendered from two places -
 * inline with the page for the initially selected day, and on its own by {@code /internal/stats/summary/{date}} as the calendar selection moves - and
 * both must show the identical card, so the parameter set is assembled here once rather than at each call site.
 */
public final class StatsSummary {

    /**
     * How many of the selected day's actions the summary shows, most-logged on that day first.
     */
    public static final int ACTION_LIMIT = 3;

    /**
     * How many of the user's enabled "Action stats" each summary row shows, in their chosen order.
     */
    public static final int FIELD_LIMIT = 3;

    private StatsSummary() {

    }

    /**
     * Binds one day's summary data onto {@code template}: the day's top actions, the fields to render for each, the user's decimal-place preference
     * and the spelled-out date.
     *
     * @param template the dashboard page or the summary partial
     * @param user the viewing user, for the display preferences
     * @param date the selected day to summarise
     * @param statsService the shared stats service
     * @return the template instance, ready for further data or rendering
     */
    public static TemplateInstance render(final Template template, final User user, final LocalDate date, final StatsService statsService) {
        return renderPrecomputed(template, user, date, statsService.forDate(user.id, date, ACTION_LIMIT));
    }

    /**
     * The variant of {@link #render(Template, User, LocalDate, StatsService)} used by the whole-month back-fill, where every day's top actions have
     * already been computed in one pass so no further query may be issued per day.
     *
     * @param template the summary partial
     * @param user the viewing user, for the display preferences
     * @param date the day being summarised
     * @param dayStats that day's top actions' stats (empty when nothing was logged)
     * @return the template instance, ready for rendering
     */
    public static TemplateInstance renderPrecomputed(final Template template, final User user, final LocalDate date,
        final List<SubjectStats> dayStats) {
        final List<DisplayStat> summaryFields = StatField.displayFields(user.statsFields)
            .stream()
            .limit(FIELD_LIMIT)
            .toList();
        return template
                .data("dayStats", dayStats)
                .data("summaryFields", summaryFields)
                .data("decimalPlaces", user.decimalPlaces)
                .data("summaryDate", date.toString())
                .data("dateLabel", DayLabels.spelledOut(date));
    }
}
