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

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.User;

/** Supplies JSON feeds consumed by the dashboard's FullCalendar (full and minimal/dot views). */
@Path("/logs")
@RolesAllowed("user")
@Produces(MediaType.APPLICATION_JSON)
public class CalendarResource {

    @Inject SecurityIdentity identity;

    /** Returns one calendar event per logged entry in the range (including archived actions). */
    @GET
    @Path("/events")
    @Transactional
    public List<CalendarEventDto> events(
            @QueryParam("start") final String start,
            @QueryParam("end") final String end) {

        final UUID userId = currentUserId();

        // FullCalendar may send ISO datetime strings; take just the date part.
        final LocalDate startDate = LocalDate.parse(start.length() > 10 ? start.substring(0, 10) : start);
        final LocalDate endDate   = LocalDate.parse(end.length()   > 10 ? end.substring(0, 10)   : end);

        // Build action map (include archived so historical logs still render).
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

    /** Returns up to four coloured dots per day for the compact "minimal" calendar view. */
    @GET
    @Path("/minimal-events")
    @Transactional
    public List<MinimalCalendarDayDto> minimalEvents(
            @QueryParam("start") final String start,
            @QueryParam("end") final String end) {

        final UUID userId = currentUserId();

        final LocalDate startDate = LocalDate.parse(start.length() > 10 ? start.substring(0, 10) : start);
        final LocalDate endDate   = LocalDate.parse(end.length()   > 10 ? end.substring(0, 10)   : end);

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

    private UUID currentUserId() {
        return User.findByEmail(identity.getPrincipal().getName())
                .map(u -> u.id)
                .orElseThrow();
    }

    /** A single FullCalendar event: title, start date and the action's colour. */
    public record CalendarEventDto(
            String title,
            String start,
            String backgroundColor,
            String borderColor) {
    }

    /** One day's worth of action dots for the minimal calendar view. */
    public record MinimalCalendarDayDto(String date, List<ActionDotDto> actions) {
    }

    /** A single coloured dot: the action's colour, name and that day's count. */
    public record ActionDotDto(String colour, String name, int count) {
    }
}
