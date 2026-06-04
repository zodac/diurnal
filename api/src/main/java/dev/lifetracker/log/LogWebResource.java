package dev.lifetracker.log;

import dev.lifetracker.action.Action;
import dev.lifetracker.user.User;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Path("/logs")
@RolesAllowed("user")
public class LogWebResource {

    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH);

    @Inject @Location("partials/day-panel")       Template dayPanelTemplate;
    @Inject @Location("partials/day-action-item") Template dayActionItemTemplate;

    @Inject SecurityIdentity identity;

    // ── Day panel ──────────────────────────────────────────────────────────

    @GET
    @Path("/day/{date}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dayPanel(@PathParam("date") LocalDate date) {
        User user = currentUser();
        List<Action> actions = Action.findActiveByUser(user.id);
        Map<UUID, Integer> counts = ActionLog.countsByAction(user.id, date);

        List<DayActionStatus> statuses = actions.stream()
                .map(a -> new DayActionStatus(a, counts.getOrDefault(a.id, 0)))
                .toList();

        return dayPanelTemplate.data(
                "date", date,
                "dateLabel", date.format(DAY_LABEL),
                "statuses", statuses);
    }

    // ── Increment ─────────────────────────────────────────────────────────

    @POST
    @Path("/{date}/{actionId}/increment")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response increment(
            @PathParam("date") LocalDate date,
            @PathParam("actionId") UUID actionId) {

        User user = currentUser();
        Action action = ownedAction(user, actionId);
        if (action == null) return Response.status(Response.Status.NOT_FOUND).build();

        ActionLog entry = ActionLog.findEntry(user.id, actionId, date);
        if (entry == null) {
            entry = new ActionLog();
            entry.userId   = user.id;
            entry.actionId = actionId;
            entry.logDate  = date;
            entry.count    = 1;
            entry.persist();
        } else if (entry.count < ActionLog.MAX_DAILY_COUNT) {
            entry.count++;
            entry.persist();
        }

        return Response.ok(item(date, action, entry.count)).build();
    }

    // ── Decrement ─────────────────────────────────────────────────────────

    @POST
    @Path("/{date}/{actionId}/decrement")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response decrement(
            @PathParam("date") LocalDate date,
            @PathParam("actionId") UUID actionId) {

        User user = currentUser();
        Action action = ownedAction(user, actionId);
        if (action == null) return Response.status(Response.Status.NOT_FOUND).build();

        ActionLog entry = ActionLog.findEntry(user.id, actionId, date);
        if (entry == null) return Response.ok(item(date, action, 0)).build();

        if (entry.count <= 1) {
            entry.delete();
            return Response.ok(item(date, action, 0)).build();
        }

        entry.count--;
        entry.persist();
        return Response.ok(item(date, action, entry.count)).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private TemplateInstance item(LocalDate date, Action action, int count) {
        return dayActionItemTemplate.data("date", date, "action", action, "count", count);
    }

    private User currentUser() {
        return User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
    }

    private Action ownedAction(User user, UUID actionId) {
        return Action.<Action>find("id = ?1 and userId = ?2 and archived = false", actionId, user.id)
                .firstResult();
    }

    public record DayActionStatus(Action action, int count) {}
}
