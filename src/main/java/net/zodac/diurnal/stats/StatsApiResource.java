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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.time.Durations;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Language;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizes;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The public REST API for a user's per-action statistics: totals, streaks, comparative trends and high scores, computed by the same
 * {@link StatsService} that drives the Stats page. Only actions with at least one logged entry are returned. Authenticates with a Bearer session
 * token (from {@code POST /api/v1/auth/login}).
 */
@Tag(name = "Stats", description = "Per-action statistics: totals, streaks, trends, high scores, etc.")
@Path("/api/v1/stats")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
@Produces(MediaType.APPLICATION_JSON)
public class StatsApiResource {

    private final CurrentUser currentUser;
    private final StatsService statsService;

    /**
     * Injects the current-user accessor and the shared stats service.
     *
     * @param currentUser the current-user accessor
     * @param statsService the shared stats service
     */
    @Inject
    public StatsApiResource(final CurrentUser currentUser, final StatsService statsService) {
        this.currentUser = currentUser;
        this.statsService = statsService;
    }

    /**
     * Returns one page of the computed statistics for every action with at least one logged entry — the same pagination the Stats page renders,
     * paged by the user's page-size preference.
     *
     * @param pageNum the 1-based page to return
     * @return the requested page of per-action statistics
     */
    @GET
    @Operation(
        summary = "List per-subject statistics",
        description = "Returns one page of computed statistics (totals, streaks, trends, high scores) for every subject with at least one entry. A "
        + "subject is either one of the user's actions or their day notes; each item says which via 'kind'. The day-notes item, when the user has "
        + "written any, is always the FIRST item of the first page and carries the nil ID; actions follow, name-ascending, with any that have never "
        + "been logged omitted. The page size is the user's 'items per page' preference; an out-of-range page is rejected with a 400 (never silently "
        + "clamped).")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The requested page of per-action statistics.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = StatsPageDto.class)))
    @APIResponse(responseCode = "400", description = "The requested page is out of range.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    public Response stats(
        @Parameter(name = "page", in = ParameterIn.QUERY,
        description = "The 1-based page to return (default 1); out-of-range values are rejected.")
        @QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User user = currentUser.get();
        final StatsInternalResource.PaginatedStats page = StatsInternalResource.paginate(statsService.forAllSubjects(user.id), pageNum,
            PageSizes.forSection(user, PageSection.STATS));
        // Surface input policy: the API rejects an out-of-range page (the web UI clamps it into range) so a
        // page number is never silently changed to some other page.
        if (pageNum < 1 || pageNum > Math.max(1, page.totalPages())) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse("Page " + pageNum + " is out of range"))
                .build();
        }
        return Response.ok(StatsPageDto.from(page)).build();
    }

    /**
     * Returns one to three actions' logged frequency over a single calendar window — the figures behind the Stats page's per-action graph.
     *
     * @param subjectId the subject to chart
     * @param compareIds the further actions to chart alongside it
     * @param period the window's period ({@code month}/{@code year})
     * @param at the window key ({@code yyyy-MM}/{@code yyyy})
     * @return the assembled frequency chart
     */
    @GET
    @Path("/{subjectId}/frequency")
    @Operation(
        summary = "Get a subject's frequency over a window",
        description = "Returns one subject's frequency over a single calendar window as an ordered series of slots: a month window yields one "
        + "slot per day, a year window one slot per month. Every slot of the window is returned, including the ones with nothing recorded, so the "
        + "series is evenly spaced. A subject is either one of the user's actions or their day notes, which are charted by passing the nil ID "
        + "'00000000-0000-0000-0000-000000000000' (one note counts as one occurrence on its day). Up to two further subjects can be charted "
        + "alongside the first with 'compare', in which case every slot carries one bar per subject and all of them are scaled against a single "
        + "peak, so the figures are directly comparable. An unrecognised period, a malformed window key, a repeated subject, more subjects than may "
        + "be charted together, or a comparison subject with no entries at all is rejected with a 400 (never silently corrected).")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The subjects' frequency over the requested window.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = FrequencyChartDto.class)))
    @APIResponse(responseCode = "400", description = "The period, the window key or the set of subjects to chart is not valid.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    @APIResponse(responseCode = "404", description = "One of the requested IDs does not belong to the authenticated user.")
    public Response frequency(
        @Parameter(name = "subjectId", in = ParameterIn.PATH,
        description = "The ID of the subject to chart: an action's ID, or the nil ID for the user's day notes.")
        @PathParam("subjectId") final UUID subjectId,
        @Parameter(name = "compare", in = ParameterIn.QUERY,
        description = "The ID of a further subject to chart alongside the first; repeatable. At most two, each of which must have at least one "
        + "entry and must not repeat a subject already being charted.")
        @QueryParam("compare") final List<UUID> compareIds,
        @Parameter(name = "period", in = ParameterIn.QUERY,
        description = "The window to chart: 'month' (one bar per day) or 'year' (one bar per month). Defaults to 'month'.")
        @QueryParam("period") final @Nullable String period,
        @Parameter(name = "at", in = ParameterIn.QUERY,
        description = "The window to chart, as 'yyyy-MM' for a month or 'yyyy' for a year. Defaults to the window containing today.")
        @QueryParam("at") final @Nullable String at) {
        final User user = currentUser.get();
        // The public API stays English regardless of the caller's own language preference (see AppMessages' class
        // Javadoc: a JSON `message`/label field is an API contract detail, not translatable UI text) - so this is
        // the one caller that always supplies Language.DEFAULT rather than the viewing user's own.
        return switch (statsService.frequency(user.id, subjectId, compareIds, period, at, Language.DEFAULT)) {
            case FrequencyResult.Charted(final FrequencyChart chart) -> Response.ok(FrequencyChartDto.from(chart)).build();
            case FrequencyResult.UnknownPeriod(final String submitted) -> badRequest("Unknown period '" + submitted + "'");
            case FrequencyResult.UnknownWindow(final String submitted) ->
                badRequest("Window '" + submitted + "' is not valid for the requested period");
            case FrequencyResult.TooManySubjects(final int submitted, final int maximum) ->
                badRequest("Cannot chart " + submitted + " actions together; the maximum is " + maximum);
            case FrequencyResult.DuplicateSubject(final UUID duplicate) -> badRequest("Action " + duplicate + " is charted more than once");
            case FrequencyResult.NotLogged(final UUID unlogged) ->
                badRequest("Action " + unlogged + " has never been logged, so it cannot be compared against");
            case final FrequencyResult.NotOwned _ -> Response.status(Response.Status.NOT_FOUND).build();
        };
    }

    private static Response badRequest(final String message) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new ApiErrorResponse(message))
            .build();
    }

    /**
     * Computed statistics for a single action, as exposed by the public API.
     *
     * @param subjectId      the subject's ID
     * @param name           the action's name
     * @param colour         the action's display colour
     * @param totalDays      the number of distinct days the action was logged
     * @param totalCount     the sum of every day's count
     * @param firstPerformed the first logged day, or {@code null} if never logged
     * @param lastPerformed  the most recent logged day, or {@code null} if never logged
     * @param currentStreak  the current run of consecutive logged days
     * @param longestStreak  the longest run of consecutive logged days
     * @param currentGap     the number of days since the action was last logged
     * @param longestGap     the longest run of consecutive unlogged days between two logged days
     * @param thisMonthCount the total count this calendar month
     * @param lastMonthCount the total count last calendar month
     * @param thisYearCount  the total count this calendar year
     * @param kind           what the statistics are about ({@code action} or {@code notes})
     * @param lastYearCount  the total count last calendar year
     * @param bestMonthLabel the label of the highest-count month (e.g. {@code June 2026})
     * @param bestMonthCount the highest single-month count
     * @param bestYearLabel  the label of the highest-count year (e.g. {@code 2026})
     * @param bestYearCount  the highest single-year count
     */
    // Public is forced: Quarkus's generated (de)serializer is not a nestmate so private throws IllegalAccessError, and the endpoint
    // taking it must be public for JAX-RS, so package-private would trip ClassEscapesItsScope instead.
    @SuppressWarnings("WeakerAccess")
    @Schema(description = "Computed statistics for a single subject: one of the user's actions, or their day notes.")
    public record SubjectStatsDto(
        @Schema(examples = "action", enumeration = {"action", "notes"},
        description = "What the statistics are about: an action, or the user's day notes.") String kind,
        @Schema(description = "The subject's ID. An action's own ID, or the nil ID for the day-notes subject.") UUID subjectId,
        @Schema(examples = "Morning run", description = "The subject's name.") String name,
        @Schema(examples = "#6366f1", description = "The subject's display colour as a CSS hex value.") String colour,
        @Schema(examples = "42", description = "The number of distinct days the subject has an entry on.") int totalDays,
        @Schema(examples = "57", description = "The sum of every day's count. For notes this equals totalDays, since a note counts once.")
        long totalCount,
        @Schema(examples = "2026-01-03", description = "The first day with an entry.") @Nullable LocalDate firstPerformed,
        @Schema(examples = "2026-06-15", description = "The most recent day with an entry.") @Nullable LocalDate lastPerformed,
        @Schema(examples = "5", description = "The current run of consecutive days with an entry.") int currentStreak,
        @Schema(examples = "14", description = "The longest run of consecutive days with an entry.") int longestStreak,
        @Schema(examples = "3", description = "The number of days since the last entry (0 if there is one today, or none ever).") int currentGap,
        @Schema(examples = "9", description = "The longest run of consecutive empty days between two entries.") int longestGap,
        @Schema(examples = "12", description = "The total count this calendar month.") long thisMonthCount,
        @Schema(examples = "18", description = "The total count last calendar month.") long lastMonthCount,
        @Schema(examples = "57", description = "The total count this calendar year.") long thisYearCount,
        @Schema(examples = "203", description = "The total count last calendar year.") long lastYearCount,
        @Schema(examples = "June 2026", description = "The label of the highest-count month.") String bestMonthLabel,
        @Schema(examples = "21", description = "The highest single-month count.") long bestMonthCount,
        @Schema(examples = "2026", description = "The label of the highest-count year.") String bestYearLabel,
        @Schema(examples = "203", description = "The highest single-year count.") long bestYearCount) {

        /**
         * Maps a computed {@link SubjectStats} to its API representation.
         *
         * @param stats the computed statistics
         * @return the DTO
         */
        static SubjectStatsDto from(final SubjectStats stats) {
            return new SubjectStatsDto(
                stats.subject().kind() == StatSubjectKind.NOTES ? "notes" : "action",
                stats.subject().id(),
                stats.subject().name(),
                stats.subject().colour(),
                stats.totalDays(),
                stats.totalCount(),
                stats.firstPerformed(),
                stats.lastPerformed(),
                Durations.days(stats.currentStreak()),
                Durations.days(stats.longestStreak()),
                SubjectStatsExtensions.currentGap(stats),
                Durations.days(stats.longestGap()),
                stats.thisMonthCount(),
                stats.lastMonthCount(),
                stats.thisYearCount(),
                stats.lastYearCount(),
                bestMonthLabel(stats.bestMonth()),
                stats.bestMonthCount(),
                stats.bestYearLabel(),
                stats.bestYearCount());
        }

        // English always, per the API's own "stays English" policy (see the frequency() method's comment) - stats.bestMonth() is the raw month
        // rather than a pre-formatted word specifically so this composer, and the web surface's own locale-aware one, can each format it
        // independently.
        private static String bestMonthLabel(final @Nullable YearMonth month) {
            return month == null ? "—" : month.format(DateTimeFormatter.ofPattern(Language.DEFAULT.monthYearPattern(), Language.DEFAULT.locale()));
        }
    }

    /**
     * One charted action's contribution to a single slot, as exposed by the public API.
     *
     * @param subjectId the charted subject's ID
     * @param count the summed count that action logged in the slot
     */
    @Schema(description = "One charted action's contribution to a single slot.")
    record FrequencyBarDto(
        @Schema(description = "The charted subject's ID.") UUID subjectId,
        @Schema(examples = "4", description = "The summed count that action logged in the slot.") long count) {

    }

    /**
     * One slot of a frequency window, as exposed by the public API.
     *
     * @param label the short axis caption ({@code 1}-{@code 31} for a day, {@code Jan}-{@code Dec} for a month)
     * @param fullLabel the slot spelled out ({@code 3 July 2026} / {@code July 2026})
     * @param bars one entry per charted action, in the same order as the chart's series
     */
    @Schema(description = "One slot of a frequency window.")
    record FrequencySlotDto(
        @Schema(examples = "3", description = "The short axis caption: the day of the month, or the abbreviated month name.") String label,
        @Schema(examples = "3 July 2026", description = "The slot spelled out in full.") String fullLabel,
        @Schema(description = "One entry per charted action, in the same order as the chart's series.") List<FrequencyBarDto> bars) {

    }

    /**
     * One charted action and its whole-window total, as exposed by the public API.
     *
     * @param subjectId the charted subject's ID
     * @param name the charted action's name
     * @param colour the charted action's display colour
     * @param total the action's summed count across the whole window
     */
    @Schema(description = "One charted action and its whole-window total.")
    record FrequencySeriesDto(
        @Schema(description = "The charted subject's ID.") UUID subjectId,
        @Schema(examples = "Morning run", description = "The charted action's name.") String name,
        @Schema(examples = "#6366f1", description = "The charted action's display colour as a CSS hex value.") String colour,
        @Schema(examples = "57", description = "The action's summed count across the whole window.") long total) {

    }

    /**
     * One to three actions' logged frequency over a single calendar window, as exposed by the public API.
     *
     * @param period the window's period
     * @param periodKey the window's key
     * @param periodLabel the window spelled out
     * @param series the charted actions, the first being the one named in the path
     * @param slots every slot of the window, in calendar order
     * @param total the summed count across every charted action and every slot
     * @param peak the tallest bar's count
     */
    @Schema(description = "One to three actions' logged frequency over a single calendar window.")
    record FrequencyChartDto(
        @Schema(examples = "month", description = "The charted window's period.") String period,
        @Schema(examples = "2026-07", description = "The charted window's key.") String periodKey,
        @Schema(examples = "July 2026", description = "The charted window spelled out.") String periodLabel,
        @Schema(description = "The charted actions, the first being the one named in the path.") List<FrequencySeriesDto> series,
        @Schema(description = "Every slot of the window in calendar order, including the ones with nothing logged.") List<FrequencySlotDto> slots,
        @Schema(examples = "57", description = "The summed count across every charted action and every slot.") long total,
        @Schema(examples = "9", description = "The tallest bar's count.") long peak) {

        private static FrequencyChartDto from(final FrequencyChart chart) {
            final List<UUID> actionIds = chart.series().stream()
                .map(FrequencySeries::subjectId)
                .toList();
            return new FrequencyChartDto(
                chart.period().value(),
                chart.periodKey(),
                chart.periodLabel(),
                chart.series().stream()
                    .map(series -> new FrequencySeriesDto(series.subjectId(), series.subjectName(), series.subjectColour(), series.total()))
                    .toList(),
                chart.slots().stream().map(slot -> slotDto(slot, actionIds)).toList(),
                chart.total(),
                chart.peak());
        }

        private static FrequencySlotDto slotDto(final FrequencySlot slot, final List<UUID> actionIds) {
            final List<FrequencyBar> slotBars = slot.bars();
            final int barCount = slotBars.size();
            final List<FrequencyBarDto> bars = new ArrayList<>(barCount);
            for (int index = 0; index < barCount; index++) {
                bars.add(new FrequencyBarDto(actionIds.get(index), slotBars.get(index).count()));
            }
            return new FrequencySlotDto(slot.label(), slot.fullLabel(), List.copyOf(bars));
        }
    }

    /**
     * One page of per-subject statistics.
     *
     * @param items       the page's statistics
     * @param totalCount  the total number of subjects
     * @param totalPages  the page count
     * @param currentPage the returned 1-based page (always the requested page — an out-of-range page is rejected, not clamped)
     */
    @Schema(description = "One page of per-subject statistics.")
    record StatsPageDto(
        @Schema(description = "The page's per-subject statistics.") List<SubjectStatsDto> items,
        @Schema(examples = "12", description = "The total number of subjects across all pages.") int totalCount,
        @Schema(examples = "3", description = "The total number of pages.") int totalPages,
        @Schema(examples = "1", description = "The returned 1-based page (always the requested page; out-of-range is rejected).") int currentPage) {

        private static StatsPageDto from(final StatsInternalResource.PaginatedStats page) {
            return new StatsPageDto(
                page.items().stream().map(SubjectStatsDto::from).toList(),
                page.totalCount(),
                page.totalPages(),
                page.currentPage());
        }
    }
}
