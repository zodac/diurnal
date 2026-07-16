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
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;

/**
 * Supplies the internal JSON feed consumed by the dashboard calendar's {@code minimal}/{@code stacked} dot/bar views: up to four coloured dots per
 * day. It is web-UI plumbing, not part of the public API — the public logged-events feed lives at {@code GET /api/v1/logs/events}
 * ({@link LogsApiResource}).
 *
 * <p>
 * The feed is {@link Compressed} — with the month back-fill it forms the dashboard's hot loading path ({@code cal.refresh()} re-pulls the visible
 * month's feed after every log mutation), and a month of events is repetitive JSON that gzips heavily. This is a targeted exception to the
 * deliberately narrow global {@code quarkus.http.compress-media-types} (see the BREACH note in {@code application.properties}), safe for the same
 * reason as {@code LogWebResource.monthPanels}: the body carries no secret (no CSRF token — protection is origin-based — and the session token never
 * appears in a body), and the only request-controlled inputs, {@code start}/{@code end}, must parse as ISO-8601 dates before anything is returned.
 */
@Path("/internal/logs")
@RolesAllowed(Role.Values.USER)
@Produces(MediaType.APPLICATION_JSON)
public class CalendarResource {

    private static final int MAX_DOTS_PER_DAY = 4;

    @Inject CurrentUser currentUser;

    /**
     * Returns up to four coloured dots per day for the compact "minimal" calendar view (internal to the dashboard).
     *
     * @param start inclusive start of the range (ISO-8601 date)
     * @param end   inclusive end of the range (ISO-8601 date)
     * @return one entry per day carrying up to four dots, sorted by date
     */
    @Compressed
    @GET
    @Path("/minimal-events")
    @Transactional
    public List<MinimalCalendarDayDto> minimalEvents(
        @QueryParam("start") final String start,
        @QueryParam("end") final String end) {

        final UUID userId = currentUser.get().id;

        final LocalDate startDate = DateRanges.requireDate("start", start);
        final LocalDate endDate   = DateRanges.requireDate("end", end);

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
                        .limit(MAX_DOTS_PER_DAY)
                        .toList();
                    return new MinimalCalendarDayDto(e.getKey(), sorted);
                })
                .toList();
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
