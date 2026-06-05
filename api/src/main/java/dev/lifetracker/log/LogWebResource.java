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
        var page = getActions(user.id, date, 1, "");

        return dayPanelTemplate.data(
                "date", date,
                "dateLabel", date.format(DAY_LABEL),
                "page", page);
    }

    @GET
    @Path("/day/{date}/list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response dayList(
            @PathParam("date") LocalDate date,
            @QueryParam("page") @DefaultValue("1") int pageNum,
            @QueryParam("q") @DefaultValue("") String searchTerm) {
        User user = currentUser();
        var page = getActions(user.id, date, pageNum, searchTerm);
        String html = renderActionsList(date, page, searchTerm);
        return Response.ok(html).build();
    }

    private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages, int currentPage) {}

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

        return new PaginatedDayActions(items, totalCount, totalPages, actualPage);
    }

    private String renderActionsList(LocalDate date, PaginatedDayActions page, String searchTerm) {
        StringBuilder sb = new StringBuilder();

        // Action items
        for (DayActionStatus status : page.items) {
            sb.append("<div id=\"day-action-").append(status.action.id).append("\" class=\"flex items-center justify-between px-3 py-3 bg-gray-50 rounded-lg group hover:bg-gray-100 transition-colors\">\n");
            sb.append("    <div class=\"flex items-center gap-2 flex-1 min-w-0\">\n");
            sb.append("        <span class=\"w-3 h-3 rounded-full flex-shrink-0\" style=\"background-color: ").append(status.action.colour).append("\"></span>\n");
            sb.append("        <span class=\"text-sm text-gray-700 truncate\">").append(escapeHtml(status.action.name)).append("</span>\n");
            sb.append("    </div>\n");
            sb.append("    <div class=\"flex items-center gap-2 flex-shrink-0 ml-2\">\n");
            sb.append("        <button hx-post=\"/logs/").append(date).append("/").append(status.action.id).append("/decrement\" hx-target=\"#day-action-").append(status.action.id).append("\" hx-swap=\"outerHTML\" class=\"bg-gray-300 text-gray-700 rounded px-2 py-1 text-xs font-medium hover:bg-gray-400 transition-colors").append(status.count == 0 ? " opacity-50 cursor-not-allowed\" disabled" : "\"").append(">−</button>\n");
            sb.append("        <span class=\"w-6 text-center text-sm font-medium").append(status.count == 0 ? " text-gray-400" : " text-gray-900").append("\">").append(status.count).append("</span>\n");
            sb.append("        <button hx-post=\"/logs/").append(date).append("/").append(status.action.id).append("/increment\" hx-target=\"#day-action-").append(status.action.id).append("\" hx-swap=\"outerHTML\" class=\"bg-indigo-600 text-white rounded px-2 py-1 text-xs font-medium hover:bg-indigo-700 transition-colors").append(status.count >= 255 ? " opacity-50 cursor-not-allowed\" disabled" : "\"").append(">+</button>\n");
            sb.append("    </div>\n");
            sb.append("</div>\n");
        }

        // Pagination controls
        if (page.totalPages > 1) {
            sb.append("<div class=\"mt-4 flex items-center justify-between text-xs text-gray-500\">\n");
            sb.append("    <p>Showing ").append(page.items.size()).append(" of ").append(page.totalCount).append("</p>\n");
            sb.append("    <div class=\"flex gap-2\">\n");

            if (page.currentPage > 1) {
                String qParam = searchTerm.isEmpty() ? "" : "&q=" + java.net.URLEncoder.encode(searchTerm, java.nio.charset.StandardCharsets.UTF_8);
                sb.append("        <a href=\"#\" hx-get=\"/logs/day/").append(date).append("/list?page=").append(page.currentPage - 1).append(qParam).append("\" hx-target=\"#day-actions-list\" class=\"text-indigo-600 hover:text-indigo-800 transition-colors\">← Previous</a>\n");
            }

            sb.append("        <span>Page ").append(page.currentPage).append(" of ").append(page.totalPages).append("</span>\n");

            if (page.currentPage < page.totalPages) {
                String qParam = searchTerm.isEmpty() ? "" : "&q=" + java.net.URLEncoder.encode(searchTerm, java.nio.charset.StandardCharsets.UTF_8);
                sb.append("        <a href=\"#\" hx-get=\"/logs/day/").append(date).append("/list?page=").append(page.currentPage + 1).append(qParam).append("\" hx-target=\"#day-actions-list\" class=\"text-indigo-600 hover:text-indigo-800 transition-colors\">Next →</a>\n");
            }

            sb.append("    </div>\n");
            sb.append("</div>\n");
        }

        return sb.toString();
    }

    private String escapeHtml(String text) {
        return text == null ? "" : text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
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
