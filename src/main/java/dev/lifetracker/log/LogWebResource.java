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
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.ZoneId;
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

    @Inject @Location("partials/day-panel")        Template dayPanelTemplate;
    @Inject @Location("partials/day-actions-list") Template dayActionsListTemplate;
    @Inject @Location("partials/day-action-item")  Template dayActionItemTemplate;

    @Inject SecurityIdentity identity;

    @ConfigProperty(name = "app.timezone", defaultValue = "UTC")
    String timezoneId;

    // ── Day panel ──────────────────────────────────────────────────────────

    @GET
    @Path("/day/{date}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dayPanel(@PathParam("date") LocalDate date) {
        User user = currentUser();
        boolean future = isFuture(date);
        var page = future ? null : getActions(user.id, date, 1, "");

        return dayPanelTemplate.data(
                "date", date,
                "dateLabel", date.format(DAY_LABEL),
                "darkMode", user.darkMode,
                "future", future,
                "page", page);
    }

    @GET
    @Path("/day/{date}/list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dayList(
            @PathParam("date") LocalDate date,
            @QueryParam("page") @DefaultValue("1") int pageNum,
            @QueryParam("q") @DefaultValue("") String searchTerm) {
        User user = currentUser();
        var page = getActions(user.id, date, pageNum, searchTerm);
        return dayActionsListTemplate.data("date", date, "page", page);
    }

    // fillerRows: blank rows that keep every paginated page the height of a full page.
    // Only populated when there is more than one page; a single short page keeps its natural height.
    private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages,
                                       int currentPage, List<Integer> fillerRows) {}

    private PaginatedDayActions getActions(UUID userId, LocalDate date, int pageNum, String searchTerm) {
        List<Action> all = Action.findActiveByUser(userId);
        Map<UUID, Integer> counts = ActionLog.countsByAction(userId, date);

        var filtered = all.stream()
                .filter(a -> searchTerm == null || searchTerm.isBlank() ||
                        a.name.toLowerCase().contains(searchTerm.toLowerCase()))
                .map(a -> new DayActionStatus(a, counts.getOrDefault(a.id, 0)))
                .toList();

        int totalCount = filtered.size();
        int pageSize = 10;
        int totalPages = (totalCount + pageSize - 1) / pageSize;
        int actualPage = Math.max(1, Math.min(pageNum, totalPages == 0 ? 1 : totalPages));
        int skip = (actualPage - 1) * pageSize;

        var items = filtered.stream()
                .skip(skip)
                .limit(pageSize)
                .toList();

        int fillers = totalPages > 1 ? Math.max(0, pageSize - items.size()) : 0;
        List<Integer> fillerRows = java.util.stream.IntStream.range(0, fillers).boxed().toList();

        return new PaginatedDayActions(items, totalCount, totalPages, actualPage, fillerRows);
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
        if (isFuture(date)) return Response.status(Response.Status.BAD_REQUEST).build();

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
        if (isFuture(date)) return Response.status(Response.Status.BAD_REQUEST).build();

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

    // Actions can only be logged for today or earlier, in the user's configured timezone.
    private boolean isFuture(LocalDate date) {
        return date.isAfter(LocalDate.now(ZoneId.of(timezoneId)));
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
