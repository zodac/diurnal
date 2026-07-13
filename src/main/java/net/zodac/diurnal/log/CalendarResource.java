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

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Supplies the JSON feeds consumed by the dashboard's calendar (the {@code full} view reads
 * {@code /logs/events}; the {@code minimal}/{@code stacked} dot/bar views read {@code /logs/minimal-events}).
 *
 * <p>The {@code /logs/events} feed is also the <strong>public API</strong> for reading a user's logged
 * actions: it is documented in the Swagger UI and may be called by external integrations with a Bearer
 * session token (see {@code /api/auth/login}) as well as by the in-app calendar with its session cookie. The
 * {@code /logs/minimal-events} feed stays internal to the dashboard.
 */
@Tag(name = "Logs", description = "Read a user's logged actions as calendar events.")
@Path("/logs")
@RolesAllowed(Role.Values.USER)
@Produces(MediaType.APPLICATION_JSON)
public class CalendarResource {

    @Inject CurrentUser currentUser;

    /**
     * Returns one calendar event per logged entry in the range.
     */
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
                description = "The 'start' or 'end' query parameter is missing or not a valid ISO-8601 date.")
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

        final LocalDate startDate = requireDate("start", start);
        final LocalDate endDate   = requireDate("end", end);

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
     * Returns up to four coloured dots per day for the compact "minimal" calendar view (internal to the dashboard).
     */
    @GET
    @Path("/minimal-events")
    @Transactional
    @Operation(hidden = true)
    public List<MinimalCalendarDayDto> minimalEvents(
            @QueryParam("start") final String start,
            @QueryParam("end") final String end) {

        final UUID userId = currentUser.get().id;

        final LocalDate startDate = requireDate("start", start);
        final LocalDate endDate   = requireDate("end", end);

        final Map<UUID, Action> actionMap = Action.<Action>list("userId = ?1", userId)
                .stream().collect(Collectors.toMap(a -> a.id, a -> a));

        // Group logs by date (TreeMap keeps dates sorted), collect one dot per action per day.
        final Map<String, List<ActionDotDto>> byDate = new TreeMap<>();
        ActionLog.findByUserAndRange(userId, startDate, endDate).stream()
                .filter(log -> actionMap.containsKey(log.actionId))
                .forEach(log -> {
                    final Action a = Objects.requireNonNull(actionMap.get(log.actionId));
                    byDate.computeIfAbsent(log.logDate.toString(), _ -> new ArrayList<>())
                          .add(new ActionDotDto(a.colour, a.name, log.count));
                });

        return byDate.entrySet().stream()
                .map(e -> {
                    final List<ActionDotDto> sorted = e.getValue().stream()
                            .sorted(Comparator.comparingInt(ActionDotDto::count).reversed()
                                    .thenComparing(ActionDotDto::name))
                            .limit(4)
                            .toList();
                    return new MinimalCalendarDayDto(e.getKey(), sorted);
                })
                .toList();
    }

    private static LocalDate requireDate(final String name, final String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Query parameter '" + name + "' is required");
        }

        final String datePart = value.length() > 10 ? value.substring(0, 10) : value;
        try {
            return LocalDate.parse(datePart);
        } catch (final DateTimeParseException e) {
            throw new BadRequestException("Query parameter '" + name + "' is not a valid ISO-8601 date: " + value, e);
        }
    }

    /**
     * A single calendar event: title, start date and the action's colour. The {@code backgroundColor}/
     * {@code borderColor} field names follow the common calendar-event convention and are kept as the
     * public API contract.
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

    /**
     * One day's worth of action dots for the minimal calendar view.
     */
    public record MinimalCalendarDayDto(String date, List<ActionDotDto> actions) {
    }

    /**
     * A single coloured dot: the action's colour, name and that day's count.
     */
    public record ActionDotDto(String colour, String name, int count) {
    }
}
