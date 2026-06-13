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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;

@Path("/actions")
@RolesAllowed("user")
public class ActionsWebResource {

    @Inject @Location("actions")       Template actionsTemplate;
    @Inject @Location("partials/actions-list") Template actionsListTemplate;
    @Inject @Location("partials/action-row") Template actionRowTemplate;
    @Inject @Location("partials/dt-confirm-delete-row") Template confirmDeleteRowTemplate;

    @Inject SecurityIdentity identity;

    // ── Full page ──────────────────────────────────────────────────────────

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance actionsPage() {
        User user = currentUser();
        var page = getActions(user.id, 1, "", user.pageSize);
        return actionsTemplate.data("displayName", user.displayName, "email", user.email, "isAdmin", user.isAdmin(), "page", page, "theme", user.theme);
    }

    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response actionsList(
            @QueryParam("page") @DefaultValue("1") int pageNum,
            @QueryParam("q") @DefaultValue("") String searchTerm) {
        User user = currentUser();
        var page = getActions(user.id, pageNum, searchTerm, user.pageSize);
        String extraQuery = (searchTerm == null || searchTerm.isBlank())
                ? ""
                : "&q=" + java.net.URLEncoder.encode(searchTerm, java.nio.charset.StandardCharsets.UTF_8);
        return Response.ok(actionsListTemplate.data("page", page, "extraQuery", extraQuery)).build();
    }

    private record PaginatedActions(List<Action> items, int totalCount, int totalPages, int currentPage) {}

    private PaginatedActions getActions(UUID userId, int pageNum, String searchTerm, int pageSize) {
        List<Action> all = Action.findActiveByUser(userId);

        var filtered = all.stream()
                .filter(a -> searchTerm == null || searchTerm.isBlank() ||
                        a.name.toLowerCase().contains(searchTerm.toLowerCase()))
                .toList();

        int totalCount = filtered.size();
        int totalPages = (totalCount + pageSize - 1) / pageSize;
        int actualPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);
        int skip = (actualPage - 1) * pageSize;

        var items = filtered.stream()
                .skip(skip)
                .limit(pageSize)
                .toList();

        return new PaginatedActions(items, totalCount, totalPages, actualPage);
    }

    // ── Partials for HTMX ─────────────────────────────────────────────────

    @GET
    @Path("{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response viewItem(@PathParam("id") UUID id) {
        Action action = findOwnedAction(id);
        if (action == null) return Response.status(404).build();
        return Response.ok(actionRowTemplate.data("action", action)).build();
    }

    @GET
    @Path("{id}/confirm-delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response confirmDelete(@PathParam("id") UUID id) {
        Action action = findOwnedAction(id);
        if (action == null) return Response.status(404).build();
        // Surgical delete: the destructive POST returns 204 and the row is removed in place
        // (see actions.html beforeSwap), so the confirm row targets its own row with outerHTML.
        return Response.ok(confirmDeleteRowTemplate
                .data("rowId", "action-" + id)
                .data("cols", 3)
                .data("swatchColour", action.colour)
                .data("label", action.name)
                .data("prompt", "Delete this action?")
                .data("deleteUrl", "/actions/" + id + "/delete")
                .data("deleteTarget", "#action-" + id)
                .data("deleteSwap", "outerHTML")
                .data("restoreUrl", "/actions/" + id)).build();
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

        return Response.ok(actionRowTemplate.data("action", action)).build();
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

        return Response.ok(actionRowTemplate.data("action", action)).build();
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
        return Response.noContent().build();
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
        // Mirrors templates/partials/banner.html so HTMX error banners match the login/register
        // pages. The `.banner*` styling is defined once in layout.html.
        String html = "<div class=\"banner banner-error\">" + message + "</div>";
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
