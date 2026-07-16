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
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.web.HtmxResponses;
import org.jspecify.annotations.Nullable;

/**
 * The web UI's internal HTMX endpoints for a user's trackable actions: the paginated list partial, single-row partials, and the create/update/delete
 * mutations that return row fragments. The full actions page stays at {@code GET /actions} ({@link ActionsWebResource}); nothing here is part of the
 * public API (that is {@code /api/v1/*}). Every mutation shares one implementation with the API ({@link ActionsApiResource}) — both surfaces call the
 * same {@link ActionService}, so the rules cannot diverge; this resource only translates {@link ActionResult} outcomes into partials/banners.
 */
@Path("/internal/actions")
@RolesAllowed(Role.Values.USER)
public class ActionsInternalResource {

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

    @Inject ActionService actionService;

    // ── Partials for HTMX ─────────────────────────────────────────────────

    /**
     * Returns the actions list partial (with optional search) for HTMX.
     *
     * @param pageNum    the 1-based page to render
     * @param searchTerm the optional case-insensitive name filter
     * @return the rendered list partial
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

    /**
     * Returns the table row for a single owned action, or {@code 404} if not found.
     *
     * @param id the action's id
     * @return the rendered row partial
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response viewItem(@PathParam("id") final UUID id) {
        final Action action = actionService.findOwned(currentUser.get(), id);
        if (action == null) {
            return Response.status(404).build();
        }
        return Response.ok(actionRowTemplate.data("action", action)).build();
    }

    /**
     * Returns the in-place confirm-delete row for an action, or {@code 404} if not found.
     *
     * @param id the action's id
     * @return the rendered confirm-delete row partial
     */
    @GET
    @Path("{id}/confirm-delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response confirmDelete(@PathParam("id") final UUID id) {
        final Action action = actionService.findOwned(currentUser.get(), id);
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
                .data("deleteUrl", "/internal/actions/" + id + "/delete")
                .data("deleteTarget", "#action-" + id)
                .data("deleteSwap", "outerHTML")
                .data("restoreUrl", "/internal/actions/" + id)).build();
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    /**
     * Creates a new action for the current user, rejecting blank or duplicate names.
     *
     * @param name   the new action's name
     * @param colour the new action's colour (a malformed value is rejected; an absent form field defaults to {@link ActionValidation#DEFAULT_COLOUR})
     * @return the rendered row partial for the created action
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response createAction(
        @FormParam("name") final String name,
        @FormParam("colour") @DefaultValue(ActionValidation.DEFAULT_COLOUR) final String colour) {
        // The form always submits a name; normalise a missing field to blank so it is rejected rather
        // than treated as a PATCH-style "keep" by the shared service.
        return translate(actionService.create(currentUser.get(), name == null ? "" : name, colour));
    }

    /**
     * Renames/recolours an existing owned action, rejecting blank or duplicate names.
     *
     * @param id     the action's id
     * @param name   the new name
     * @param colour the new colour (a malformed value is rejected; an absent form field defaults to {@link ActionValidation#DEFAULT_COLOUR})
     * @return the rendered row partial for the updated action
     */
    @POST
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response updateAction(
        @PathParam("id") final UUID id,
        @FormParam("name") final String name,
        @FormParam("colour") @DefaultValue(ActionValidation.DEFAULT_COLOUR) final String colour) {
        // The edit form always submits both fields; normalise a missing name to blank so it is rejected
        // rather than treated as a PATCH-style "keep" by the shared service.
        return translate(actionService.update(currentUser.get(), id, name == null ? "" : name, colour));
    }

    /**
     * Hard-deletes an owned action and its logs, returning {@code 204}.
     *
     * @param id the action's id
     * @return {@code 204} on success, {@code 404} if not found
     */
    @POST
    @Path("{id}/delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response deleteAction(@PathParam("id") final UUID id) {
        if (actionService.delete(currentUser.get(), id) instanceof ActionResult.NotFound) {
            return Response.status(404).build();
        }
        return Response.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Fetches, filters and pages a user's actions in memory. Shared with the full-page render ({@link ActionsWebResource}).
     *
     * @param userId     the owning user's id
     * @param pageNum    the requested 1-based page (clamped into range)
     * @param searchTerm the optional case-insensitive name filter
     * @param pageSize   the user's page size
     * @return the requested page of actions
     */
    static PaginatedActions getActions(final UUID userId, final int pageNum, final @Nullable String searchTerm, final int pageSize) {
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

    private Response translate(final ActionResult result) {
        return switch (result) {
            case ActionResult.Success success -> Response.ok(actionRowTemplate.data("action", success.action())).build();
            case ActionResult.BlankName ignored -> HtmxResponses.conflictBanner("#action-error", "Action name cannot be empty.");
            case ActionResult.NameTooLong ignored -> HtmxResponses.conflictBanner("#action-error",
                "Action name cannot be longer than " + ActionValidation.NAME_MAX_LENGTH + " characters.");
            case ActionResult.InvalidColour ignored -> HtmxResponses.conflictBanner("#action-error",
                "Action colour is invalid");
            case ActionResult.DuplicateName duplicate ->
                HtmxResponses.conflictBanner("#action-error", "An action named '" + duplicate.name() + "' already exists.");
            case ActionResult.NotFound ignored -> Response.status(404).build();
        };
    }

    /**
     * One page of a user's actions, as rendered by the list partial and the full page.
     *
     * @param items       the page's actions
     * @param totalCount  the filtered total across all pages
     * @param totalPages  the page count
     * @param currentPage the rendered (clamped) 1-based page
     */
    record PaginatedActions(List<Action> items, int totalCount, int totalPages, int currentPage) {

    }
}
