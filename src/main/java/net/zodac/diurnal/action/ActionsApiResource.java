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

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The public REST API for a user's trackable actions: list, create, read, update and delete. Authenticates with a Bearer session token (from
 * {@code POST /api/v1/auth/login}). Every mutation shares one implementation with the web UI ({@link ActionsInternalResource}) — both surfaces call
 * the same {@link ActionService}, so the rules cannot diverge; this resource only translates {@link ActionResult} outcomes into JSON.
 */
@Tag(name = "Actions", description = "Manage a user's personal actions.")
@Path("/api/v1/actions")
@RolesAllowed(Role.Values.USER)
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ActionsApiResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    ActionService actionService;

    /**
     * Lists one page of the user's actions, ordered by name and optionally filtered by a case-insensitive name search — the same pagination the
     * Actions page renders, paged by the user's page-size preference.
     *
     * @param pageNum    the 1-based page to return
     * @param searchTerm the optional case-insensitive name filter
     * @return the requested page of actions
     */
    @GET
    @Transactional
    @Operation(
        summary = "List actions",
        description = "Returns one page of the user's actions, ordered by name and optionally filtered by a case-insensitive name search. The "
        + "page size is the user's 'items per page' preference; an out-of-range page is rejected with a 400 (never silently clamped).")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The requested page of actions.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ActionPageDto.class))),
        @APIResponse(responseCode = "400", description = "The requested page is out of range.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    })
    public Response listActions(
        @Parameter(name = "page", in = ParameterIn.QUERY, description = "The 1-based page to return (default 1); out-of-range values are rejected.")
        @QueryParam("page") @DefaultValue("1") final int pageNum,
        @Parameter(name = "q", in = ParameterIn.QUERY, description = "Optional case-insensitive name filter.")
        @QueryParam("q") @DefaultValue("") final String searchTerm) {
        final User user = currentUser.get();
        final ActionsInternalResource.PaginatedActions page = ActionsInternalResource.getActions(user.id, pageNum, searchTerm, user.pageSize);
        // Surface input policy: the API rejects an out-of-range page (the web UI clamps it into range) so a
        // page number is never silently changed to some other page.
        if (pageNum < 1 || pageNum > Math.max(1, page.totalPages())) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse("Page " + pageNum + " is out of range"))
                .build();
        }
        return Response.ok(ActionPageDto.from(page)).build();
    }

    /**
     * Creates a new action, rejecting blank, over-long or duplicate names.
     *
     * @param request the new action's name and optional colour
     * @return {@code 201} with the created action
     */
    @POST
    @Transactional
    @Operation(
        summary = "Create an action",
        description = "Creates a new action. A missing colour uses the default, but a malformed colour is rejected (never silently corrected); names "
        + "must be unique per user.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "The action was created.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ActionDto.class))),
        @APIResponse(responseCode = "400", description = "The name is missing, blank, or longer than 100 characters, or the colour is malformed.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "409", description = "An action with this name already exists.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public Response createAction(final @Nullable ActionRequest request) {
        // A create request requires the name; normalise an absent body/field to blank so the shared service
        // rejects it rather than treating it as a PATCH-style "keep".
        final String name = request == null || request.name() == null ? "" : request.name();
        final String colour = request == null ? null : request.colour();
        return translate(actionService.create(currentUser.get(), name, colour), Response.Status.CREATED);
    }

    /**
     * Returns a single owned action.
     *
     * @param id the action's id
     * @return the action, or {@code 404}
     */
    @GET
    @Path("{id}")
    @Transactional
    @Operation(summary = "Get an action", description = "Returns a single action owned by the user.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The action.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ActionDto.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "No such action owned by this user.")
    })
    public Response getAction(
        @Parameter(name = "id", in = ParameterIn.PATH, required = true, description = "The action's id.")
        @PathParam("id") final UUID id) {
        final Action action = actionService.findOwned(currentUser.get(), id);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(ActionDto.from(action)).build();
    }

    /**
     * Renames and/or recolours an owned action. Only the fields present in the body change: an absent {@code name} keeps the current name, an absent
     * {@code colour} keeps the current colour (a malformed colour is rejected, never silently corrected).
     *
     * @param id      the action's id
     * @param request the fields to change
     * @return the updated action
     */
    @PATCH
    @Path("{id}")
    @Transactional
    @Operation(
        summary = "Update an action",
        description = "Renames and/or recolours an action. Absent fields keep their current value.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The updated action.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ActionDto.class))),
        @APIResponse(responseCode = "400", description = "The new name is blank or longer than 100 characters, or the colour is malformed.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "No such action owned by this user."),
        @APIResponse(responseCode = "409", description = "An action with the new name already exists.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public Response updateAction(
        @Parameter(name = "id", in = ParameterIn.PATH, required = true, description = "The action's id.")
        @PathParam("id") final UUID id,
        final @Nullable ActionRequest request) {
        // PATCH semantics: absent fields stay null and the shared service keeps their current values.
        final String name = request == null ? null : request.name();
        final String colour = request == null ? null : request.colour();
        return translate(actionService.update(currentUser.get(), id, name, colour), Response.Status.OK);
    }

    /**
     * Hard-deletes an owned action <strong>and every log entry recorded against it</strong> (there is no soft-delete or archive).
     *
     * @param id the action's id
     * @return {@code 204} on success
     */
    @DELETE
    @Path("{id}")
    @Transactional
    @Operation(
        summary = "Delete an action",
        description = "Hard-deletes an action AND all of its logged entries. This cannot be undone.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "The action and its logs were deleted."),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "No such action owned by this user.")
    })
    public Response deleteAction(
        @Parameter(name = "id", in = ParameterIn.PATH, required = true, description = "The action's id.")
        @PathParam("id") final UUID id) {
        if (actionService.delete(currentUser.get(), id) instanceof ActionResult.NotFound) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.noContent().build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static Response translate(final ActionResult result, final Response.Status successStatus) {
        return switch (result) {
            case ActionResult.Success success -> Response.status(successStatus).entity(ActionDto.from(success.action())).build();
            case ActionResult.BlankName ignored -> Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse("Action name cannot be empty"))
                .build();
            case ActionResult.NameTooLong ignored -> Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse("Action name cannot be longer than " + ActionValidation.NAME_MAX_LENGTH + " characters"))
                .build();
            case ActionResult.InvalidColour ignored -> Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse("Action colour is invalid"))
                .build();
            case ActionResult.DuplicateName duplicate -> Response.status(Response.Status.CONFLICT)
                .entity(new ApiErrorResponse("An action named '" + duplicate.name() + "' already exists"))
                .build();
            case ActionResult.NotFound ignored -> Response.status(Response.Status.NOT_FOUND).build();
        };
    }

    /**
     * The body for creating or updating an action.
     *
     * @param name   the action's name (required on create; optional on update)
     * @param colour the action's colour as {@code #rrggbb} (optional; a malformed value is rejected, an absent one uses the default on create)
     */
    @Schema(description = "Fields for creating or updating an action.")
    public record ActionRequest(
        @Schema(examples = "Morning run", description = "The action's name; unique per user, at most 100 characters.") @Nullable String name,
        @Schema(examples = "#6366f1",
        description = "The action's display colour as a CSS hex value; a malformed value is rejected, omit to use the default on create.")
        @Nullable String colour) {
    }

    /**
     * A single trackable action.
     *
     * @param id     the action's id
     * @param name   the action's name
     * @param colour the action's display colour as {@code #rrggbb}
     */
    @Schema(description = "A single trackable action (habit).")
    public record ActionDto(
        @Schema(description = "The action's id.") UUID id,
        @Schema(examples = "Morning run", description = "The action's name.") String name,
        @Schema(examples = "#6366f1", description = "The action's display colour as a CSS hex value.") String colour) {

        /**
         * Maps an {@link Action} entity to its API representation.
         *
         * @param action the entity
         * @return the DTO
         */
        public static ActionDto from(final Action action) {
            return new ActionDto(action.id, action.name, action.colour);
        }
    }

    /**
     * One page of actions.
     *
     * @param items       the page's actions
     * @param totalCount  the (filtered) total number of actions
     * @param totalPages  the page count
     * @param currentPage the returned 1-based page (always the requested page — an out-of-range page is rejected, not clamped)
     */
    @Schema(description = "One page of the user's actions, ordered by name.")
    public record ActionPageDto(
        @Schema(description = "The page's actions, ordered by name.") List<ActionDto> items,
        @Schema(examples = "12", description = "The total number of (filtered) actions across all pages.") int totalCount,
        @Schema(examples = "3", description = "The total number of pages.") int totalPages,
        @Schema(examples = "1", description = "The returned 1-based page (always the requested page; out-of-range is rejected).") int currentPage) {

        /**
         * Maps the shared pagination result to its API representation.
         *
         * @param page the fetched page
         * @return the DTO
         */
        static ActionPageDto from(final ActionsInternalResource.PaginatedActions page) {
            return new ActionPageDto(
                page.items().stream().map(ActionDto::from).toList(),
                page.totalCount(),
                page.totalPages(),
                page.currentPage());
        }
    }
}
