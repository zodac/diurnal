package dev.lifetracker.action;

import dev.lifetracker.log.ActionLog;
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

import java.util.List;
import java.util.UUID;

@Path("/actions")
@RolesAllowed("user")
public class ActionsWebResource {

    @Inject @Location("actions")       Template actionsTemplate;
    @Inject @Location("partials/action-item") Template actionItemTemplate;
    @Inject @Location("partials/action-edit") Template actionEditTemplate;

    @Inject SecurityIdentity identity;

    // ── Full page ──────────────────────────────────────────────────────────

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance actionsPage() {
        User user = currentUser();
        var page = getActions(user.id, 1, "");
        return actionsTemplate.data("displayName", user.displayName, "email", user.email, "page", page, "darkMode", user.darkMode);
    }

    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response actionsList(
            @QueryParam("page") @DefaultValue("1") int pageNum,
            @QueryParam("q") @DefaultValue("") String searchTerm) {
        User user = currentUser();
        var page = getActions(user.id, pageNum, searchTerm);
        String html = renderActionsList(page, searchTerm);
        return Response.ok(html).build();
    }

    private record PaginatedActions(List<Action> items, int totalCount, int totalPages, int currentPage) {}

    private PaginatedActions getActions(UUID userId, int pageNum, String searchTerm) {
        List<Action> all = Action.findActiveByUser(userId);

        var filtered = all.stream()
                .filter(a -> searchTerm == null || searchTerm.isBlank() ||
                        a.name.toLowerCase().contains(searchTerm.toLowerCase()))
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

        return new PaginatedActions(items, totalCount, totalPages, actualPage);
    }

    private String renderActionsList(PaginatedActions page, String searchTerm) {
        StringBuilder sb = new StringBuilder();

        // Actions list items
        for (Action action : page.items) {
            sb.append("<div id=\"action-").append(action.id).append("\" class=\"flex items-center gap-3 px-4 py-3 bg-white dark:bg-gray-700 rounded-xl border border-gray-200 dark:border-gray-600 group\">\n");
            sb.append("    <span class=\"w-4 h-4 rounded-full flex-shrink-0 border border-black/10\" style=\"background-color: ").append(action.colour).append("\"></span>\n");
            sb.append("    <span class=\"flex-1 text-sm font-medium text-gray-800 dark:text-gray-200\">").append(escapeHtml(action.name)).append("</span>\n");
            sb.append("    <div class=\"flex gap-2 opacity-0 group-hover:opacity-100 transition-opacity\">\n");
            sb.append("        <button hx-get=\"/actions/").append(action.id).append("/edit\" hx-target=\"#action-").append(action.id).append("\" hx-swap=\"outerHTML\" class=\"text-xs text-gray-400 dark:text-gray-500 hover:text-indigo-600 dark:hover:text-indigo-400 transition-colors\">Edit</button>\n");
            sb.append("        <button hx-post=\"/actions/").append(action.id).append("/delete\" hx-target=\"#action-").append(action.id).append("\" hx-swap=\"outerHTML swap:1s\" class=\"text-xs text-gray-400 dark:text-gray-500 hover:text-red-600 dark:hover:text-red-400 transition-colors\">Delete</button>\n");
            sb.append("    </div>\n");
            sb.append("</div>\n");
        }

        // Pagination controls
        if (page.totalPages > 1) {
            sb.append("<div class=\"mt-4 flex items-center justify-between text-xs text-gray-500 dark:text-gray-400\">\n");
            sb.append("    <p>Showing ").append(page.items.size()).append(" of ").append(page.totalCount).append("</p>\n");
            sb.append("    <div class=\"flex gap-2\">\n");

            if (page.currentPage > 1) {
                String qParam = searchTerm.isEmpty() ? "" : "&q=" + java.net.URLEncoder.encode(searchTerm, java.nio.charset.StandardCharsets.UTF_8);
                sb.append("        <a href=\"/actions?page=").append(page.currentPage - 1).append(qParam).append("\" hx-get=\"/actions/list?page=").append(page.currentPage - 1).append(qParam).append("\" hx-target=\"#action-list\" class=\"text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 transition-colors\">← Previous</a>\n");
            }

            sb.append("        <span>Page ").append(page.currentPage).append(" of ").append(page.totalPages).append("</span>\n");

            if (page.currentPage < page.totalPages) {
                String qParam = searchTerm.isEmpty() ? "" : "&q=" + java.net.URLEncoder.encode(searchTerm, java.nio.charset.StandardCharsets.UTF_8);
                sb.append("        <a href=\"/actions?page=").append(page.currentPage + 1).append(qParam).append("\" hx-get=\"/actions/list?page=").append(page.currentPage + 1).append(qParam).append("\" hx-target=\"#action-list\" class=\"text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 transition-colors\">Next →</a>\n");
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

    // ── Partials for HTMX ─────────────────────────────────────────────────

    @GET
    @Path("{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response viewItem(@PathParam("id") UUID id) {
        Action action = findOwnedAction(id);
        if (action == null) return Response.status(404).build();
        return Response.ok(actionItemTemplate.data("action", action)).build();
    }

    @GET
    @Path("{id}/edit")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response editForm(@PathParam("id") UUID id) {
        Action action = findOwnedAction(id);
        if (action == null) return Response.status(404).build();
        return Response.ok(actionEditTemplate.data("action", action)).build();
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response createAction(
            @FormParam("name") String name,
            @FormParam("colour") @DefaultValue("#6366f1") String colour) {

        if (name == null || name.isBlank()) {
            return errorResponse("Action name cannot be empty.");
        }

        User user = currentUser();
        String normName = name.strip();

        if (Action.count("userId = ?1 and name = ?2 and archived = false", user.id, normName) > 0) {
            return errorResponse("An action named '" + normName + "' already exists.");
        }

        Action action = new Action();
        action.userId = user.id;
        action.name = normName;
        action.colour = sanitiseColour(colour);
        action.persist();

        return Response.ok(actionItemTemplate.data("action", action)).build();
    }

    @POST
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response updateAction(
            @PathParam("id") UUID id,
            @FormParam("name") String name,
            @FormParam("colour") @DefaultValue("#6366f1") String colour) {

        Action action = findOwnedAction(id);
        if (action == null) return Response.status(404).build();

        if (name == null || name.isBlank()) {
            return errorResponse("Action name cannot be empty.");
        }

        String normName = name.strip();

        if (Action.count("userId = ?1 and name = ?2 and archived = false and id != ?3", action.userId, normName, id) > 0) {
            return errorResponse("An action named '" + normName + "' already exists.");
        }

        action.name = normName;
        action.colour = sanitiseColour(colour);
        action.persist();

        return Response.ok(actionItemTemplate.data("action", action)).build();
    }

    @POST
    @Path("{id}/delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response deleteAction(@PathParam("id") UUID id) {
        Action action = findOwnedAction(id);
        if (action == null) return Response.status(404).build();
        // Remove the action's logged entries too, so they no longer appear on the calendar.
        ActionLog.deleteByAction(action.userId, action.id);
        action.archived = true;
        action.persist();
        return Response.ok("").build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private User currentUser() {
        return User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
    }

    private Action findOwnedAction(UUID id) {
        User user = currentUser();
        return Action.<Action>find("id = ?1 and userId = ?2 and archived = false", id, user.id)
                .firstResult();
    }

    private Response errorResponse(String message) {
        String html = "<p class=\"text-sm text-red-600\">" + message + "</p>";
        return Response.status(Response.Status.CONFLICT)
                .entity(html)
                .header("HX-Retarget", "#action-error")
                .header("HX-Reswap", "innerHTML")
                .build();
    }

    private static String sanitiseColour(String colour) {
        if (colour != null && colour.matches("^#[0-9a-fA-F]{6}$")) {
            return colour;
        }
        return "#6366f1";
    }
}
