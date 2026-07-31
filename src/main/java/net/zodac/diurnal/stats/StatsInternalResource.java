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

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.vertx.http.Compressed;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * The web UI's internal endpoints for stats fragments: the Stats page's paginated stats-cards list partial, and the dashboard's per-day summary card
 * (one day at a time, plus a whole-month bulk variant the dashboard caches). The full page stays at {@code GET /stats}
 * ({@link StatsWebResource}); nothing here is part of the public API (that is {@code /api/v1/*}).
 */
@Path("/internal/stats")
@RolesAllowed(Role.Values.USER)
public class StatsInternalResource {

    private final Template statsCardsTemplate;
    private final Template statsSummaryTemplate;
    private final CurrentUser currentUser;
    private final StatsService statsService;

    /**
     * Injects the stats-cards and stats-summary partial templates, the current-user accessor and the shared stats service.
     *
     * @param statsCardsTemplate the stats-cards list partial template
     * @param statsSummaryTemplate the dashboard stats-summary card partial template
     * @param currentUser the current-user accessor
     * @param statsService the shared stats service
     */
    @Inject
    public StatsInternalResource(@Location("partials/stats-cards") final Template statsCardsTemplate,
        @Location("partials/stats-summary") final Template statsSummaryTemplate, final CurrentUser currentUser,
        final StatsService statsService) {
        this.statsCardsTemplate = statsCardsTemplate;
        this.statsSummaryTemplate = statsSummaryTemplate;
        this.currentUser = currentUser;
        this.statsService = statsService;
    }

    /**
     * Returns the dashboard stats-summary card for a single day - the day's most-logged actions, each with the user's chosen stat tiles.
     *
     * <p>
     * The dashboard re-fetches this every time the calendar selection moves, so it renders the same partial the page embeds for its initial day and
     * nothing else: the client swaps the response straight into the {@code #stats-summary} wrapper.
     *
     * @param date the selected day to summarise
     * @return the rendered summary card (empty when nothing was logged that day)
     */
    @GET
    @Path("/summary/{date}")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance summary(@PathParam("date") final LocalDate date) {
        final User user = currentUser.get();
        return StatsSummary.render(statsSummaryTemplate, user, date, statsService);
    }

    /**
     * Renders every day of a month's summary card in ONE response: a JSON map of ISO date to summary HTML.
     *
     * <p>
     * The dashboard loads the selected day on its own, then calls this once to back-fill the rest of the month into its client-side cache, so moving
     * the selection between days repaints the summary instantly. Doing it as a single request lets {@link StatsService#forMonth(java.util.UUID,
     * YearMonth, int)} read the month's logs once and aggregate the union of every day's top actions once, rather than paying both per day.
     *
     * <p>
     * Like the day-panel back-fill it answers with ~30 rendered partials in one JSON body, so it is explicitly {@link Compressed} - a targeted
     * exception to the deliberately narrow global {@code quarkus.http.compress-media-types} (see the BREACH note in {@code application.properties}).
     * That is safe here for the same reasons: the body carries no secret, and the only request-controlled input, the {@code month} path segment, must
     * parse as {@code yyyy-MM} before anything is rendered.
     *
     * @param month the month to render, as {@code yyyy-MM}
     * @return {@code 200} with a JSON object mapping each {@code yyyy-MM-dd} to its summary HTML, or {@code 400} when {@code month} is not a valid
     *     {@code yyyy-MM}
     */
    @Compressed
    @GET
    @Path("/summary-month/{month}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response summaryMonth(@PathParam("month") final String month) {
        final YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month);
        } catch (final DateTimeParseException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        final User user = currentUser.get();
        final Map<LocalDate, List<ActionStats>> byDate = statsService.forMonth(user.id, yearMonth, StatsSummary.ACTION_LIMIT);

        final Map<String, String> cards = new LinkedHashMap<>();
        final LocalDate end = yearMonth.atEndOfMonth();
        for (LocalDate date = yearMonth.atDay(1); !date.isAfter(end); date = date.plusDays(1)) {
            cards.put(date.toString(),
                StatsSummary.renderPrecomputed(statsSummaryTemplate, user, date, byDate.getOrDefault(date, List.of())).render());
        }
        return Response.ok(cards).build();
    }

    /**
     * Returns just the stats-cards list partial for HTMX pagination.
     *
     * @param pageNum the 1-based page to render
     * @return the rendered list partial
     */
    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance statsList(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User user = currentUser.get();
        return statsCardsTemplate
                .data("decimalPlaces", user.decimalPlaces)
                .data("statsFields", ActionStatField.displayFields(user.statsFields))
                .data("page", paginate(statsService.forAllActiveActions(user.id), pageNum, user.pageSize));
    }

    /**
     * Pages a pre-computed list of per-action stats in memory. Shared with the full-page render ({@link StatsWebResource}). Only actions with at
     * least one logged entry are in the list ({@code forAllActiveActions} filters them).
     *
     * @param all      every active action's stats, in display order
     * @param pageNum  the requested 1-based page (clamped into range)
     * @param pageSize the user's page size
     * @return the requested page of stats
     */
    static PaginatedStats paginate(final List<ActionStats> all, final int pageNum, final int pageSize) {
        final int totalCount = all.size();
        final int totalPages = (totalCount + pageSize - 1) / pageSize;
        final int actualPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);
        final int skip = (actualPage - 1) * pageSize;

        final List<ActionStats> items = all.stream()
            .skip(skip)
            .limit(pageSize)
            .toList();

        return new PaginatedStats(items, totalCount, totalPages, actualPage);
    }

    /**
     * One page of per-action stats, as rendered by the list partial and the full page.
     *
     * @param items       the page's stats
     * @param totalCount  the total number of active actions
     * @param totalPages  the page count
     * @param currentPage the rendered (clamped) 1-based page
     */
    record PaginatedStats(List<ActionStats> items, int totalCount, int totalPages, int currentPage) {

    }
}
