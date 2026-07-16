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

package net.zodac.diurnal.log;

import io.quarkus.vertx.http.Compressed;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The public REST API for a user's logged actions: {@code GET /api/v1/logs/events} returns one calendar event per logged entry in a date range,
 * {@code GET /api/v1/logs/{date}} returns one day's counts, and the {@code PUT}/{@code POST}/{@code DELETE} endpoints write a day's count for an
 * action (set, atomic increment/decrement, and remove). External integrations call these with a Bearer session token (see
 * {@code POST /api/v1/auth/login}); the dashboard's {@code full} calendar view reads the events feed with its session cookie. The write endpoints
 * share one implementation with the web UI ({@link LogWebResource}) — both surfaces call the same {@link LogService}, so the write rules (logging
 * blocked for future dates in the user's timezone, the {@link ActionLog#MAX_DAILY_COUNT} ceiling, a count of zero removing the day's entry) cannot
 * diverge; this resource only translates {@link LogResult} outcomes into JSON. Where the surfaces deliberately differ is their <em>input</em>
 * contract at that ceiling: the web form saturates an over-cap write to the cap, whereas this API rejects it with a {@code 400} (see the per-endpoint
 * notes) rather than silently changing the caller's value.
 *
 * <p>
 * The feed is {@link Compressed} — with the dashboard's month back-fill it forms the calendar's hot loading path ({@code cal.refresh()} re-pulls the
 * visible month's feed after every log mutation), and a month of events is repetitive JSON that gzips heavily. This is a targeted exception to the
 * deliberately narrow global {@code quarkus.http.compress-media-types} (see the BREACH note in {@code application.properties}), safe for the same
 * reason as {@code LogWebResource.monthPanels}: the body carries no secret (no CSRF token — protection is origin-based — and the session token never
 * appears in a body), and the only request-controlled inputs, {@code start}/{@code end}, must parse as ISO-8601 dates before anything is returned.
 */
@Tag(name = "Logs", description = "Read and write a user's logged actions.")
@Path("/api/v1/logs")
@RolesAllowed(Role.Values.USER)
@Produces(MediaType.APPLICATION_JSON)
public class LogsApiResource {

    private static final String FUTURE_DATE_MESSAGE = "Cannot log against a future date";

    @Inject CurrentUser currentUser;

    @Inject LogService logService;

    /**
     * Returns one calendar event per logged entry in the range.
     *
     * @param start inclusive start of the range (ISO-8601 date)
     * @param end   inclusive end of the range (ISO-8601 date)
     * @return the logged events in the range
     */
    @Compressed
    @GET
    @Path("/events")
    @Transactional
    @Operation(
        summary = "List logged events in a date range",
        description = "Returns all logged actions within the date range for the user."
    )
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Logged events in the range (one entry per logged action per day).",
                content = @Content(mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(type = SchemaType.ARRAY, implementation = CalendarEventDto.class))),
        @APIResponse(responseCode = "400",
                description = "The 'start' or 'end' query parameter is missing or not a valid ISO-8601 date."),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    })
    public List<CalendarEventDto> events(
        @Parameter(name = "start", in = ParameterIn.QUERY, required = true,
        description = "Inclusive start of the range, as an ISO-8601 date (yyyy-MM-dd); only the date part is used.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-01"))
        @QueryParam("start") final String start,
        @Parameter(name = "end", in = ParameterIn.QUERY, required = true,
        description = "Inclusive end of the range, as an ISO-8601 date (yyyy-MM-dd); only the date part is used.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-30"))
        @QueryParam("end") final String end) {

        final UUID userId = currentUser.get().id;

        final LocalDate startDate = DateRanges.requireDate("start", start);
        final LocalDate endDate   = DateRanges.requireDate("end", end);

        final Map<UUID, Action> actionMap = Action.<Action>list("userId = ?1", userId)
            .stream().collect(Collectors.toMap(a -> a.id, a -> a));

        return ActionLog.findByUserAndRange(userId, startDate, endDate).stream()
                .filter(log -> actionMap.containsKey(log.actionId))
                .map(log -> {
                    final Action a = Objects.requireNonNull(actionMap.get(log.actionId));
                    final String title = log.count > 1 ? a.name + " ×" + log.count : a.name;
                    return new CalendarEventDto(title, log.logDate.toString(), a.colour, a.colour);
                })
                .toList();
    }

    /**
     * Returns one entry per action logged on the given day (actions without a log entry that day are omitted).
     *
     * @param date the day to read, as an ISO-8601 date
     * @return the day's logged counts
     */
    @GET
    @Path("/{date}")
    @Transactional
    @Operation(
        summary = "Get a day's logged counts",
        description = "Returns one entry per action logged on the given day; actions with no entry that day are omitted.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The day's logged counts.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(type = SchemaType.ARRAY, implementation = DayLogEntryDto.class))),
        @APIResponse(responseCode = "400", description = "The date is not a valid ISO-8601 date."),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    })
    public Response dayLogs(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to read, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date) {
        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);

        final Map<UUID, Integer> counts = ActionLog.countsByAction(user.id, day);
        if (counts.isEmpty()) {
            return Response.ok(List.of()).build();
        }
        final List<DayLogEntryDto> entries = Action.findByUserAndIds(user.id, counts.keySet()).stream()
            .map(a -> new DayLogEntryDto(a.id, a.name, a.colour, Objects.requireNonNull(counts.get(a.id))))
            .sorted(java.util.Comparator.comparing(DayLogEntryDto::name))
            .toList();
        return Response.ok(entries).build();
    }

    /**
     * Sets the day's count for an action to an explicit value: {@code 0} removes the day's entry, a value above {@link ActionLog#MAX_DAILY_COUNT} is
     * rejected (never silently clamped).
     *
     * @param date     the day to write, as an ISO-8601 date
     * @param actionId the action's id
     * @param request  the count to set
     * @return the resulting count for the day
     */
    @PUT
    @Path("/{date}/{actionId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(
        summary = "Set a day's count for an action",
        description = "Sets the count to an explicit value: 0 removes the day's entry; a value above 999 is rejected with a 400 (never silently "
        + "clamped). Future dates (in the user's timezone) are rejected.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The resulting count.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = LogEntryDto.class))),
        @APIResponse(responseCode = "400", description = "The date is invalid or in the future, or the count is missing, negative or above 999.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "No such action owned by this user.")
    })
    public Response updateCount(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to write, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @Parameter(name = "actionId", in = ParameterIn.PATH, required = true, description = "The id of the action being logged against.")
        @PathParam("actionId") final UUID actionId,
        final @Nullable SetCountRequest request) {

        // The API's input contract: a missing or negative count is an error (the web form instead
        // coerces it to zero), and a count above the cap is an error (the web form instead saturates
        // it to the cap) — per-surface translations of intent, not different write rules.
        final Integer requested = request == null ? null : request.count();
        if (requested == null || requested < 0) {
            return badRequest("A non-negative 'count' is required");
        }
        if (requested > ActionLog.MAX_DAILY_COUNT) {
            return badRequest("'count' cannot exceed " + ActionLog.MAX_DAILY_COUNT);
        }

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return translate(logService.updateCount(user, day, actionId, requested), actionId, day);
    }

    /**
     * Atomically increments the day's count for an action by {@code amount} (default 1); an increment that would push the count above
     * {@link ActionLog#MAX_DAILY_COUNT} is rejected (never silently clamped).
     *
     * @param date     the day to write, as an ISO-8601 date
     * @param actionId the action's id
     * @param request  the optional amount (default 1)
     * @return the resulting count for the day
     */
    @POST
    @Path("/{date}/{actionId}/increment")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(
        summary = "Increment a day's count for an action",
        description = "Atomically increments the count by 'amount' (default 1). An increment that would push the count above 999 is rejected with a "
        + "400 (never silently clamped). Race-safe for concurrent clients. Future dates (in the user's timezone) are rejected.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The resulting count.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = LogEntryDto.class))),
        @APIResponse(responseCode = "400", description = "The date is invalid or in the future, the amount is below 1, or the increment would "
                + "exceed 999.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "No such action owned by this user.")
    })
    public Response increment(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to write, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @Parameter(name = "actionId", in = ParameterIn.PATH, required = true, description = "The id of the action being logged against.")
        @PathParam("actionId") final UUID actionId,
        @RequestBody(required = false, description = "Optional adjustment amount; omit the body (or the field) to adjust by 1.")
        final @Nullable AmountRequest request) {
        return adjust(date, actionId, request, true);
    }

    /**
     * Atomically decrements the day's count for an action by {@code amount} (default 1), removing the day's entry when it reaches zero.
     *
     * @param date     the day to write, as an ISO-8601 date
     * @param actionId the action's id
     * @param request  the optional amount (default 1)
     * @return the resulting count for the day
     */
    @POST
    @Path("/{date}/{actionId}/decrement")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(
        summary = "Decrement a day's count for an action",
        description = "Atomically decrements the count by 'amount' (default 1), removing the day's entry at zero. Race-safe for concurrent "
        + "clients. Future dates (in the user's timezone) are rejected.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The resulting count.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = LogEntryDto.class))),
        @APIResponse(responseCode = "400", description = "The date is invalid or in the future, or the amount is below 1.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "No such action owned by this user.")
    })
    public Response decrement(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to write, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @Parameter(name = "actionId", in = ParameterIn.PATH, required = true, description = "The id of the action being logged against.")
        @PathParam("actionId") final UUID actionId,
        @RequestBody(required = false, description = "Optional adjustment amount; omit the body (or the field) to adjust by 1.")
        final @Nullable AmountRequest request) {
        return adjust(date, actionId, request, false);
    }

    /**
     * Removes the day's log entry for an action. Removing an entry that does not exist is a no-op (still {@code 204}).
     *
     * @param date     the day to clear, as an ISO-8601 date
     * @param actionId the action's id
     * @return {@code 204}
     */
    @DELETE
    @Path("/{date}/{actionId}")
    @Transactional
    @Operation(
        summary = "Remove a day's log entry for an action",
        description = "Deletes the day's entry for the action (equivalent to setting the count to 0). A missing entry is a no-op.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "The entry was removed (or did not exist)."),
        @APIResponse(responseCode = "400", description = "The date is invalid or in the future.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "No such action owned by this user.")
    })
    public Response deleteEntry(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to clear, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @Parameter(name = "actionId", in = ParameterIn.PATH, required = true, description = "The id of the action being logged against.")
        @PathParam("actionId") final UUID actionId) {
        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return switch (logService.deleteEntry(user, day, actionId)) {
            case LogResult.FutureDate ignored -> badRequest(FUTURE_DATE_MESSAGE);
            case LogResult.NotOwned ignored -> Response.status(Response.Status.NOT_FOUND).build();
            case LogResult.Updated ignored -> Response.noContent().build();
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Response adjust(final String date, final UUID actionId, final @Nullable AmountRequest request, final boolean increment) {
        // The API's input contract: an amount below 1 is an error (the web form instead coerces it to a
        // no-op) — a per-surface translation of intent, not a different write rule.
        final Integer requested = request == null ? null : request.amount();
        final int amount = requested == null ? 1 : requested;
        if (amount < 1) {
            return badRequest("'amount' must be at least 1");
        }

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);

        // The API's input contract: an increment that would push the count above the cap is an error (the
        // web form instead saturates it to the cap) — a per-surface translation of intent, not a different
        // write rule. Applying the shared guards first (future date → ownership) keeps the failure order
        // identical to a write; a decrement can never exceed the cap, so it needs no such check.
        if (increment) {
            final LogResult current = logService.readCount(user, day, actionId);
            if (!(current instanceof final LogResult.Updated existing)) {
                return translate(current, actionId, day);
            }
            if ((long) existing.count() + amount > ActionLog.MAX_DAILY_COUNT) {
                return badRequest("Count cannot exceed " + ActionLog.MAX_DAILY_COUNT);
            }
        }
        return translate(logService.adjust(user, day, actionId, amount, increment), actionId, day);
    }

    private static Response translate(final LogResult result, final UUID actionId, final LocalDate day) {
        return switch (result) {
            case LogResult.FutureDate ignored -> badRequest(FUTURE_DATE_MESSAGE);
            case LogResult.NotOwned ignored -> Response.status(Response.Status.NOT_FOUND).build();
            case LogResult.Updated updated -> Response.ok(new LogEntryDto(actionId, day.toString(), updated.count())).build();
        };
    }

    private static Response badRequest(final String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse(message))
                .build();
    }

    /**
     * The body for setting a day's count.
     *
     * @param count the count to set; {@code 0} removes the entry
     */
    @Schema(description = "The count to set for the day.")
    public record SetCountRequest(
        @Schema(examples = "3", description = "The count to set; 0 removes the day's entry, a value above 999 is rejected.")
        @Nullable Integer count) {
    }

    /**
     * The optional body for incrementing or decrementing a day's count.
     *
     * @param amount the amount to adjust by (default 1)
     */
    @Schema(description = "The amount to adjust the day's count by.")
    public record AmountRequest(
        @Schema(examples = "1", description = "The amount to adjust by; must be at least 1. Defaults to 1 when omitted.")
        @Nullable Integer amount) {
    }

    /**
     * One action's resulting count for a day, returned by every write endpoint.
     *
     * @param actionId the action's id
     * @param date     the day, as an ISO-8601 date string
     * @param count    the resulting count (0 means no entry remains)
     */
    @Schema(description = "One action's resulting count for a day.")
    public record LogEntryDto(
        @Schema(description = "The action's id.") UUID actionId,
        @Schema(examples = "2026-06-15", description = "The day, as an ISO-8601 date string.") String date,
        @Schema(examples = "3", description = "The resulting count for the day; 0 means no entry remains.") int count) {
    }

    /**
     * One action's logged count on a given day.
     *
     * @param actionId the action's id
     * @param name     the action's name
     * @param colour   the action's display colour
     * @param count    the logged count for the day
     */
    @Schema(description = "One action's logged count on a given day.")
    public record DayLogEntryDto(
        @Schema(description = "The action's id.") UUID actionId,
        @Schema(examples = "Morning run", description = "The action's name.") String name,
        @Schema(examples = "#6366f1", description = "The action's display colour as a CSS hex value.") String colour,
        @Schema(examples = "3", description = "The logged count for the day.") int count) {
    }

    /**
     * A single calendar event: title, start date and the action's colour. The {@code backgroundColor}/ {@code borderColor} field names follow the
     * common calendar-event convention and are kept as the public API contract.
     */
    @Schema(description = "A single logged-action calendar event: title, date and the action's display colour.")
    public record CalendarEventDto(
        @Schema(examples = "Morning run", description = "Action name; includes a ×N suffix when the day's count exceeds 1.") String title,
        @Schema(examples = "2026-06-15", description = "Date of the logged entry as an ISO-8601 date string.") String start,
        @Schema(examples = "#6366f1", description = "Action colour as a CSS hex value, used as the event background colour.")
        String backgroundColor,
        @Schema(examples = "#6366f1", description = "Action colour as a CSS hex value, used as the event border colour.")
        String borderColor) {
    }
}
