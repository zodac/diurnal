package dev.lifetracker.log;

import dev.lifetracker.action.Action;
import dev.lifetracker.user.User;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/logs")
@RolesAllowed("user")
@Produces(MediaType.APPLICATION_JSON)
public class CalendarResource {

    @Inject SecurityIdentity identity;

    @GET
    @Path("/events")
    @Transactional
    public List<CalendarEventDto> events(
            @QueryParam("start") String start,
            @QueryParam("end") String end) {

        UUID userId = currentUserId();

        // FullCalendar may send ISO datetime strings; take just the date part.
        LocalDate startDate = LocalDate.parse(start.length() > 10 ? start.substring(0, 10) : start);
        LocalDate endDate   = LocalDate.parse(end.length()   > 10 ? end.substring(0, 10)   : end);

        // Build action map (include archived so historical logs still render).
        Map<UUID, Action> actionMap = Action.<Action>list("userId = ?1", userId)
                .stream().collect(Collectors.toMap(a -> a.id, a -> a));

        return ActionLog.findByUserAndRange(userId, startDate, endDate).stream()
                .filter(log -> actionMap.containsKey(log.actionId))
                .map(log -> {
                    Action a = actionMap.get(log.actionId);
                    String title = log.count > 1 ? a.name + " ×" + log.count : a.name;
                    return new CalendarEventDto(title, log.logDate.toString(), a.colour, a.colour);
                })
                .toList();
    }

    @GET
    @Path("/minimal-events")
    @Transactional
    public List<MinimalCalendarDayDto> minimalEvents(
            @QueryParam("start") String start,
            @QueryParam("end") String end) {

        UUID userId = currentUserId();

        LocalDate startDate = LocalDate.parse(start.length() > 10 ? start.substring(0, 10) : start);
        LocalDate endDate   = LocalDate.parse(end.length()   > 10 ? end.substring(0, 10)   : end);

        Map<UUID, Action> actionMap = Action.<Action>list("userId = ?1", userId)
                .stream().collect(Collectors.toMap(a -> a.id, a -> a));

        // Group logs by date (TreeMap keeps dates sorted), collect one dot per action per day.
        Map<String, List<ActionDotDto>> byDate = new TreeMap<>();
        ActionLog.findByUserAndRange(userId, startDate, endDate).stream()
                .filter(log -> actionMap.containsKey(log.actionId))
                .forEach(log -> {
                    Action a = actionMap.get(log.actionId);
                    byDate.computeIfAbsent(log.logDate.toString(), k -> new ArrayList<>())
                          .add(new ActionDotDto(a.colour, a.name, log.count));
                });

        return byDate.entrySet().stream()
                .map(e -> {
                    List<ActionDotDto> sorted = e.getValue().stream()
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

    public record CalendarEventDto(
            String title,
            String start,
            String backgroundColor,
            String borderColor) {}

    public record MinimalCalendarDayDto(String date, List<ActionDotDto> actions) {}

    public record ActionDotDto(String colour, String name, int count) {}
}
