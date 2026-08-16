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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.page.PageWindow;
import net.zodac.diurnal.page.Pages;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizes;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The web UI's internal endpoints for stats fragments: the Stats page's paginated stats-cards list partial, and the dashboard's per-day summary card
 * (one day at a time, plus a whole-month bulk variant the dashboard caches). The full page stays at {@code GET /stats}
 * ({@link StatsWebResource}); nothing here is part of the public API (that is {@code /api/v1/*}).
 */
@Path("/internal/stats")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
public class StatsInternalResource {

    private static final Logger LOGGER = LogManager.getLogger(StatsInternalResource.class);

    private final Template statsCardsTemplate;
    private final Template statsSummaryTemplate;
    private final Template statsChartTemplate;
    private final Template statsChartCandidatesTemplate;
    private final CurrentUser currentUser;
    private final StatsService statsService;

    /**
     * Injects the stats-cards, stats-summary, stats-chart and compare-picker partial templates, the current-user accessor and the shared stats
     * service.
     *
     * @param statsCardsTemplate the stats-cards list partial template
     * @param statsSummaryTemplate the dashboard stats-summary card partial template
     * @param statsChartTemplate the frequency-chart partial template
     * @param statsChartCandidatesTemplate the frequency chart's compare-picker candidate-list partial template
     * @param currentUser the current-user accessor
     * @param statsService the shared stats service
     */
    @Inject
    public StatsInternalResource(@Location("partials/stats-cards") final Template statsCardsTemplate,
        @Location("partials/stats-summary") final Template statsSummaryTemplate,
        @Location("partials/stats-chart") final Template statsChartTemplate,
        @Location("partials/stats-chart-candidates") final Template statsChartCandidatesTemplate, final CurrentUser currentUser,
        final StatsService statsService) {
        this.statsCardsTemplate = statsCardsTemplate;
        this.statsSummaryTemplate = statsSummaryTemplate;
        this.statsChartTemplate = statsChartTemplate;
        this.statsChartCandidatesTemplate = statsChartCandidatesTemplate;
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
            LOGGER.trace("Rejecting monthly summary request: '{}' is not a valid yyyy-MM", month, e);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        final User user = currentUser.get();
        final Map<LocalDate, List<SubjectStats>> byDate = statsService.forMonth(user.id, yearMonth, StatsSummary.ACTION_LIMIT);

        final Map<String, String> cards = new LinkedHashMap<>();
        final LocalDate end = yearMonth.atEndOfMonth();
        for (LocalDate date = yearMonth.atDay(1); !date.isAfter(end); date = date.plusDays(1L)) {
            cards.put(date.toString(),
                StatsSummary.renderPrecomputed(statsSummaryTemplate, user, date, byDate.getOrDefault(date, List.of())).render());
        }
        return Response.ok(cards).build();
    }

    /**
     * Returns the frequency-chart partial for one action over one window - the body of the Stats page's graph modal, re-fetched every time the user
     * flips the period toggle or steps to a neighbouring window.
     *
     * <p>
     * Rendering server-side (rather than shipping the figures as JSON and drawing them in the browser) keeps the bars, their captions and their hover
     * values on the same code path as every other rendered figure, so the modal needs no client-side charting at all - only the fetch and the swap.
     *
     * @param subjectId the subject to chart
     * @param period the window's period ({@code month}/{@code year}); defaults to {@link FrequencyPeriod#DEFAULT}
     * @param at the window key ({@code yyyy-MM}/{@code yyyy}); defaults to the window containing today
     * @return {@code 200} with the rendered chart, {@code 400} for an unrecognised period or malformed window, or {@code 404} when the action is not
     *     the user's
     */
    @GET
    @Path("/chart/{subjectId}")
    @Produces(MediaType.TEXT_HTML)
    public Response chart(@PathParam("subjectId") final UUID subjectId, @QueryParam("compare") final List<UUID> compareIds,
        @QueryParam("period") final @Nullable String period, @QueryParam("at") final @Nullable String at) {
        final User user = currentUser.get();
        return switch (statsService.frequency(user.id, subjectId, compareIds, period, at)) {
            case FrequencyResult.Charted(final FrequencyChart chart) -> Response.ok(statsChartTemplate
                .data("chart", chart)
                .data("decimalPlaces", user.decimalPlaces)
                .data("candidates", statsService.compareCandidates(user.id, chartedIds(chart), null))).build();
            case final FrequencyResult.UnknownPeriod _ -> Response.status(Response.Status.BAD_REQUEST).build();
            case final FrequencyResult.UnknownWindow _ -> Response.status(Response.Status.BAD_REQUEST).build();
            case final FrequencyResult.TooManySubjects _ -> Response.status(Response.Status.BAD_REQUEST).build();
            case final FrequencyResult.DuplicateSubject _ -> Response.status(Response.Status.BAD_REQUEST).build();
            case final FrequencyResult.NotLogged _ -> Response.status(Response.Status.BAD_REQUEST).build();
            case final FrequencyResult.NotOwned _ -> Response.status(Response.Status.NOT_FOUND).build();
        };
    }

    /**
     * Returns the frequency chart's compare-picker list: the user's actions that can still be added to the graph, name-filtered by {@code q}. The
     * same partial the chart fragment embeds for its unfiltered first render, so the search box can swap it in place as the user types.
     *
     * <p>
     * Deliberately has NO {@code /api/v1} twin (surface policy): it is a picker affordance, not a capability of its own. The data behind it - which
     * actions have been logged at least once - is already the exact set {@code GET /api/v1/stats} returns, and the comparison itself is available
     * through the {@code compare} parameter on {@code GET /api/v1/stats/{actionId}/frequency}.
     *
     * @param subjectId the subject the graph was opened from
     * @param compareIds the actions already being compared, which are never offered again
     * @param query the case-insensitive name filter
     * @return the rendered candidate list
     */
    @GET
    @Path("/chart/{subjectId}/candidates")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance chartCandidates(@PathParam("subjectId") final UUID subjectId, @QueryParam("compare") final List<UUID> compareIds,
        @QueryParam("q") final @Nullable String query) {
        final User user = currentUser.get();
        final List<UUID> charted = new ArrayList<>();
        charted.add(subjectId);
        charted.addAll(compareIds);
        return statsChartCandidatesTemplate.data("candidates", statsService.compareCandidates(user.id, charted, query));
    }

    private static List<UUID> chartedIds(final FrequencyChart chart) {
        return chart.series().stream()
            .map(FrequencySeries::subjectId)
            .toList();
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
                .data("statsFields", StatField.displayFields(user.statsFields))
                .data("page", paginate(statsService.forAllSubjects(user.id), pageNum, PageSizes.forSection(user, PageSection.STATS)));
    }

    /**
     * Pages a pre-computed list of per-subject stats in memory. Shared with the full-page render ({@link StatsWebResource}). Only subjects with data
     * are in the list ({@code forAllSubjects} filters them), and the notes subject is already pinned to the front of it — so it lands on page one
     * rather than on whichever page it happened to fall.
     *
     * @param all      every subject's stats, in display order
     * @param pageNum  the requested 1-based page (clamped into range)
     * @param pageSize the user's page size
     * @return the requested page of stats
     */
    static PaginatedStats paginate(final List<SubjectStats> all, final int pageNum, final int pageSize) {
        final PageWindow window = Pages.window(all.size(), pageNum, pageSize);
        return new PaginatedStats(Pages.slice(all, window), all.size(), window.totalPages(), window.currentPage());
    }

    /**
     * One page of per-action stats, as rendered by the list partial and the full page.
     *
     * @param items       the page's stats
     * @param totalCount  the total number of active actions
     * @param totalPages  the page count
     * @param currentPage the rendered (clamped) 1-based page
     */
    record PaginatedStats(List<SubjectStats> items, int totalCount, int totalPages, int currentPage) {

    }
}
