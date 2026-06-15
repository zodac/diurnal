package dev.lifetracker.log;

import dev.lifetracker.action.Action;
import dev.lifetracker.time.AppClock;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

@Path("/logs")
@RolesAllowed("user")
public class LogWebResource {

    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH);

    @Inject @Location("partials/day-panel")        Template dayPanelTemplate;
    @Inject @Location("partials/day-actions-list") Template dayActionsListTemplate;
    @Inject @Location("partials/day-action-item")  Template dayActionItemTemplate;

    @Inject SecurityIdentity identity;
    @Inject AppClock clock;

    // ── Day panel ──────────────────────────────────────────────────────────

    @GET
    @Path("/day/{date}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dayPanel(@PathParam("date") LocalDate date) {
        User user = currentUser();
        boolean future = isFuture(date, user);
        var page = future ? null : getActions(user.id, date, 1, "", user.pageSize);

        return dayPanelTemplate
            .data("date", date)
            .data("dateLabel", date.format(DAY_LABEL))
            .data("theme", user.theme)
            .data("future", future)
            .data("page", page);
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
        var page = getActions(user.id, date, pageNum, searchTerm, user.pageSize);
        return dayActionsListTemplate.data("date", date, "page", page);
    }

    // fillerRows: blank rows that keep every paginated page the height of a full page.
    // Only populated when there is more than one page; a single short page keeps its natural height.
    private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages,
                                       int currentPage, List<Integer> fillerRows) {}

    private PaginatedDayActions getActions(UUID userId, LocalDate date, int pageNum, String searchTerm, int pageSize) {
        List<Action> all = Action.findActiveByUser(userId);
        Map<UUID, Integer> counts = ActionLog.countsByAction(userId, date);

        var filtered = all.stream()
                .filter(a -> searchTerm == null || searchTerm.isBlank() ||
                        a.name.toLowerCase().contains(searchTerm.toLowerCase()))
                .map(a -> new DayActionStatus(a, counts.getOrDefault(a.id, 0)))
                // Highest count first; equal counts (including 0) keep the DB's alphabetical
                // order, since `all` arrives sorted by name and sorted() is stable.
                .sorted(Comparator.comparingInt(DayActionStatus::count).reversed())
                .toList();

        int totalCount = filtered.size();
        int totalPages = (totalCount + pageSize - 1) / pageSize;
        int actualPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);
        int skip = (actualPage - 1) * pageSize;

        var items = filtered.stream()
                .skip(skip)
                .limit(pageSize)
                .toList();

        int fillers = totalPages > 1 ? Math.max(0, pageSize - items.size()) : 0;
        List<Integer> fillerRows = IntStream.range(0, fillers).boxed().toList();

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
        if (isFuture(date, user)) return Response.status(Response.Status.BAD_REQUEST).build();

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
        if (isFuture(date, user)) return Response.status(Response.Status.BAD_REQUEST).build();

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

    // Actions can only be logged for today or earlier, in the user's configured timezone
    // (falling back to the server default when the user hasn't chosen one).
    private boolean isFuture(LocalDate date, User user) {
        return date.isAfter(clock.today(clock.zoneFor(user.timezone)));
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
