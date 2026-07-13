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

package net.zodac.diurnal.action;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
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
import java.util.Locale;
import java.util.UUID;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CRUD endpoints for a user's trackable actions, returning full pages and HTMX partials.
 */
@Path("/actions")
@RolesAllowed(Role.Values.USER)
public class ActionsWebResource {

    private static final Logger LOGGER = LogManager.getLogger(ActionsWebResource.class);

    @Inject
    @Location("actions")
    Template actionsTemplate;

    @Inject
    @Location("partials/actions-list")
    Template actionsListTemplate;

    @Inject
    @Location("partials/action-row")
    Template actionRowTemplate;

    @Inject
    @Location("partials/dt-confirm-delete-row")
    Template confirmDeleteRowTemplate;

    @Inject CurrentUser currentUser;

    // ── Full page ──────────────────────────────────────────────────────────

    /**
     * Renders the full actions page for the current user.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance actionsPage() {
        final User user = currentUser.get();
        final var page = getActions(user.id, 1, "", user.pageSize);
        return actionsTemplate
                .data("displayName", user.displayName)
                .data("email", user.email)
                .data("isAdmin", user.isAdmin())
                .data("page", page)
                .data("theme", user.theme)
                .data("font", user.font);
    }

    /**
     * Returns the actions list partial (with optional search) for HTMX.
     */
    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response actionsList(
        @QueryParam("page") @DefaultValue("1") final int pageNum,
        @QueryParam("q") @DefaultValue("") final String searchTerm) {
        final User user = currentUser.get();
        final var page = getActions(user.id, pageNum, searchTerm, user.pageSize);
        final String extraQuery = (searchTerm == null || searchTerm.isBlank())
            ? ""
            : "&q=" + java.net.URLEncoder.encode(searchTerm, java.nio.charset.StandardCharsets.UTF_8);
        return Response.ok(actionsListTemplate.data("page", page, "extraQuery", extraQuery)).build();
    }

    private record PaginatedActions(List<Action> items, int totalCount, int totalPages, int currentPage) {

    }

    private PaginatedActions getActions(final UUID userId, final int pageNum, final String searchTerm, final int pageSize) {
        final List<Action> all = Action.findByUser(userId);

        final var filtered = all.stream()
            .filter(a -> searchTerm == null || searchTerm.isBlank()
            || a.name.toLowerCase(Locale.ROOT).contains(searchTerm.toLowerCase(Locale.ROOT)))
            .toList();

        final int totalCount = filtered.size();
        final int totalPages = (totalCount + pageSize - 1) / pageSize;
        final int actualPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);
        final int skip = (actualPage - 1) * pageSize;

        final var items = filtered.stream()
            .skip(skip)
            .limit(pageSize)
            .toList();

        return new PaginatedActions(items, totalCount, totalPages, actualPage);
    }

    // ── Partials for HTMX ─────────────────────────────────────────────────

    /**
     * Returns the table row for a single owned action, or {@code 404} if not found.
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response viewItem(@PathParam("id") final UUID id) {
        final Action action = findOwnedAction(id);
        if (action == null) {
            return Response.status(404).build();
        }
        return Response.ok(actionRowTemplate.data("action", action)).build();
    }

    /**
     * Returns the in-place confirm-delete row for an action, or {@code 404} if not found.
     */
    @GET
    @Path("{id}/confirm-delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response confirmDelete(@PathParam("id") final UUID id) {
        final Action action = findOwnedAction(id);
        if (action == null) {
            return Response.status(404).build();
        }
        // Surgical delete: the destructive POST returns 204 and the row is removed in place
        // (see actions.html beforeSwap), so the confirmation row targets its own row with outerHTML.
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

    /**
     * Creates a new action for the current user, rejecting blank or duplicate names.
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response createAction(
        @FormParam("name") final String name,
        @FormParam("colour") @DefaultValue("#64748b") final String colour) {

        if (name == null || name.isBlank()) {
            return errorResponse("Action name cannot be empty.");
        }

        final User user = currentUser.get();
        final String normName = name.strip();

        if (Action.count("userId = ?1 and name = ?2", user.id, normName) > 0) {
            return errorResponse("An action named '" + normName + "' already exists.");
        }

        final Action action = new Action();
        action.userId = user.id;
        action.name = normName;
        action.colour = sanitiseColour(colour);
        action.persist();

        LOGGER.info("Action created: {} (colour={}) for user {}", action.id, action.colour, user.email);
        return Response.ok(actionRowTemplate.data("action", action)).build();
    }

    /**
     * Renames/recolours an existing owned action, rejecting blank or duplicate names.
     */
    @POST
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response updateAction(
        @PathParam("id") final UUID id,
        @FormParam("name") final String name,
        @FormParam("colour") @DefaultValue("#64748b") final String colour) {

        final Action action = findOwnedAction(id);
        if (action == null) {
            return Response.status(404).build();
        }

        if (name == null || name.isBlank()) {
            return errorResponse("Action name cannot be empty.");
        }

        final String normName = name.strip();

        if (Action.count("userId = ?1 and name = ?2 and id != ?3", action.userId, normName, id) > 0) {
            return errorResponse("An action named '" + normName + "' already exists.");
        }

        action.name = normName;
        action.colour = sanitiseColour(colour);
        action.persist();

        LOGGER.info("Action updated: {} (colour={}) for user {}", action.id, action.colour, currentUser.get().email);
        return Response.ok(actionRowTemplate.data("action", action)).build();
    }

    /**
     * Hard-deletes an owned action and its logs, returning {@code 204}.
     */
    @POST
    @Path("{id}/delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response deleteAction(@PathParam("id") final UUID id) {
        final Action action = findOwnedAction(id);
        if (action == null) {
            return Response.status(404).build();
        }
        // Remove the action's logged entries first, then the action itself.
        ActionLog.deleteByAction(action.userId, action.id);
        action.delete();
        LOGGER.info("Action deleted: {} for user {}", action.id, currentUser.get().email);
        return Response.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Action findOwnedAction(final UUID id) {
        final User user = currentUser.get();
        return Action.<Action>find("id = ?1 and userId = ?2", id, user.id)
                .firstResult();
    }

    private Response errorResponse(final String message) {
        // Mirrors templates/partials/banner.html so HTMX error banners match the login/register
        // pages. The `.banner*` styling is defined once in layout.html.
        final String html = "<div class=\"banner banner-error\">" + message + "</div>";
        return Response.status(Response.Status.CONFLICT)
                .entity(html)
                .header("HX-Retarget", "#action-error")
                .header("HX-Reswap", "innerHTML")
                .build();
    }

    private static String sanitiseColour(final String colour) {
        if (colour != null && colour.matches("^#[0-9a-fA-F]{6}$")) {
            return colour;
        }
        return "#64748b";
    }
}
