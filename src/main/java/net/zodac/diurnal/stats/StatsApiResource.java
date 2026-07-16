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
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
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
@RolesAllowed(Role.Values.USER)
@Produces(MediaType.APPLICATION_JSON)
public class StatsApiResource {

    @Inject CurrentUser currentUser;

    @Inject StatsService statsService;

    /**
     * Returns one page of the computed statistics for every action with at least one logged entry — the same pagination the Stats page renders,
     * paged by the user's page-size preference.
     *
     * @param pageNum the 1-based page to return
     * @return the requested page of per-action statistics
     */
    @GET
    @Transactional
    @Operation(
        summary = "List per-action statistics",
        description = "Returns one page of computed statistics (totals, streaks, trends, high scores) for every action with at least one "
        + "logged entry; actions that have never been logged are omitted. The page size is the user's 'items per page' preference; an "
        + "out-of-range page is rejected with a 400 (never silently clamped).")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The requested page of per-action statistics.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = StatsPageDto.class))),
        @APIResponse(responseCode = "400", description = "The requested page is out of range.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    })
    public Response stats(
        @Parameter(name = "page", in = ParameterIn.QUERY,
        description = "The 1-based page to return (default 1); out-of-range values are rejected.")
        @QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User user = currentUser.get();
        final StatsInternalResource.PaginatedStats page = StatsInternalResource.paginate(statsService.forAllActiveActions(user.id), pageNum,
            user.pageSize);
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
     * Computed statistics for a single action, as exposed by the public API.
     *
     * @param actionId       the action's id
     * @param name           the action's name
     * @param colour         the action's display colour
     * @param totalDays      the number of distinct days the action was logged
     * @param totalCount     the sum of every day's count
     * @param firstPerformed the first logged day, or {@code null} if never logged
     * @param lastPerformed  the most recent logged day, or {@code null} if never logged
     * @param currentStreak  the current run of consecutive logged days
     * @param longestStreak  the longest run of consecutive logged days
     * @param longestGap     the longest run of consecutive unlogged days between two logged days
     * @param thisMonthCount the total count this calendar month
     * @param lastMonthCount the total count last calendar month
     * @param thisYearCount  the total count this calendar year
     * @param lastYearCount  the total count last calendar year
     * @param bestMonthLabel the label of the highest-count month (e.g. {@code 2026-06})
     * @param bestMonthCount the highest single-month count
     * @param bestYearLabel  the label of the highest-count year (e.g. {@code 2026})
     * @param bestYearCount  the highest single-year count
     */
    @Schema(description = "Computed statistics for a single action.")
    public record ActionStatsDto(
        @Schema(description = "The action's id.") UUID actionId,
        @Schema(examples = "Morning run", description = "The action's name.") String name,
        @Schema(examples = "#6366f1", description = "The action's display colour as a CSS hex value.") String colour,
        @Schema(examples = "42", description = "The number of distinct days the action was logged.") int totalDays,
        @Schema(examples = "57", description = "The sum of every day's count.") long totalCount,
        @Schema(examples = "2026-01-03", description = "The first logged day.") @Nullable LocalDate firstPerformed,
        @Schema(examples = "2026-06-15", description = "The most recent logged day.") @Nullable LocalDate lastPerformed,
        @Schema(examples = "5", description = "The current run of consecutive logged days.") int currentStreak,
        @Schema(examples = "14", description = "The longest run of consecutive logged days.") int longestStreak,
        @Schema(examples = "9", description = "The longest run of consecutive unlogged days between two logged days.") int longestGap,
        @Schema(examples = "12", description = "The total count this calendar month.") long thisMonthCount,
        @Schema(examples = "18", description = "The total count last calendar month.") long lastMonthCount,
        @Schema(examples = "57", description = "The total count this calendar year.") long thisYearCount,
        @Schema(examples = "203", description = "The total count last calendar year.") long lastYearCount,
        @Schema(examples = "2026-06", description = "The label of the highest-count month.") String bestMonthLabel,
        @Schema(examples = "21", description = "The highest single-month count.") long bestMonthCount,
        @Schema(examples = "2026", description = "The label of the highest-count year.") String bestYearLabel,
        @Schema(examples = "203", description = "The highest single-year count.") long bestYearCount) {

        /**
         * Maps a computed {@link ActionStats} to its API representation.
         *
         * @param stats the computed statistics
         * @return the DTO
         */
        public static ActionStatsDto from(final ActionStats stats) {
            return new ActionStatsDto(
                stats.action().id,
                stats.action().name,
                stats.action().colour,
                stats.totalDays(),
                stats.totalCount(),
                stats.firstPerformed(),
                stats.lastPerformed(),
                stats.currentStreak(),
                stats.longestStreak(),
                stats.longestGap(),
                stats.thisMonthCount(),
                stats.lastMonthCount(),
                stats.thisYearCount(),
                stats.lastYearCount(),
                stats.bestMonthLabel(),
                stats.bestMonthCount(),
                stats.bestYearLabel(),
                stats.bestYearCount());
        }
    }

    /**
     * One page of per-action statistics.
     *
     * @param items       the page's statistics
     * @param totalCount  the total number of active actions
     * @param totalPages  the page count
     * @param currentPage the returned 1-based page (always the requested page — an out-of-range page is rejected, not clamped)
     */
    @Schema(description = "One page of per-action statistics.")
    public record StatsPageDto(
        @Schema(description = "The page's per-action statistics.") List<ActionStatsDto> items,
        @Schema(examples = "12", description = "The total number of active actions across all pages.") int totalCount,
        @Schema(examples = "3", description = "The total number of pages.") int totalPages,
        @Schema(examples = "1", description = "The returned 1-based page (always the requested page; out-of-range is rejected).") int currentPage) {

        /**
         * Maps the shared pagination result to its API representation.
         *
         * @param page the fetched page
         * @return the DTO
         */
        static StatsPageDto from(final StatsInternalResource.PaginatedStats page) {
            return new StatsPageDto(
                page.items().stream().map(ActionStatsDto::from).toList(),
                page.totalCount(),
                page.totalPages(),
                page.currentPage());
        }
    }
}
