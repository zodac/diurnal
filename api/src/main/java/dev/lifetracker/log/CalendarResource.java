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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Path("/logs/events")
@RolesAllowed("user")
@Produces(MediaType.APPLICATION_JSON)
public class CalendarResource {

    @Inject SecurityIdentity identity;

    @GET
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
}
