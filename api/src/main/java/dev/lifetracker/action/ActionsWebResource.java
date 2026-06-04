package dev.lifetracker.action;

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
        List<Action> actions = Action.findActiveByUser(user.id);
        return actionsTemplate.data("displayName", user.displayName, "email", user.email, "actions", actions);
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
        return Response.ok(html)
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
