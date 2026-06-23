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

package net.zodac.diurnal.web;

import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Admin-only user management: list, change role, and delete users (with last-admin safeguards).
 */
@Path("/admin")
@RolesAllowed("admin")
public class AdminWebResource {

    private static final Logger LOGGER = LogManager.getLogger(AdminWebResource.class);

    @Inject
    @Location("admin-users")
    Template adminUsersTemplate;
    @Inject
    @Location("admin-api-docs")
    Template adminApiDocsTemplate;
    @Inject
    @Location("partials/admin-users-list")
    Template adminUsersListTemplate;
    @Inject
    @Location("partials/admin-user-row")
    Template adminUserRowTemplate;
    @Inject
    @Location("partials/dt-confirm-delete-row")
    Template confirmDeleteRowTemplate;
    @Inject SecurityIdentity identity;
    @Inject AppClock clock;

    /**
     * Renders the paginated admin users page.
     */
    @GET
    @Path("users")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance usersPage(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User actor = currentUser();
        return adminUsersTemplate
                .data("email", actor.email)
                .data("displayName", actor.displayName)
                .data("theme", actor.theme)
                .data("isAdmin", true)
                .data("page", getUsersPage(pageNum, actor.pageSize));
    }

    /**
     * Renders the embedded API documentation page (Swagger UI in an in-app iframe).
     */
    @GET
    @Path("api-docs")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance apiDocsPage() {
        final User actor = currentUser();
        return adminApiDocsTemplate
                .data("email", actor.email)
                .data("displayName", actor.displayName)
                .data("theme", actor.theme)
                .data("isAdmin", true);
    }

    /**
     * Returns just the users list partial for HTMX pagination.
     */
    @GET
    @Path("users/list")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance usersList(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User actor = currentUser();
        return adminUsersListTemplate.data("page", getUsersPage(pageNum, actor.pageSize));
    }

    /**
     * Returns the single table row for one user (used to restore a row after cancel).
     */
    @GET
    @Path("users/{id}")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response userRow(@PathParam("id") final UUID id) {
        final User target = User.findById(id);
        if (target == null) {
            return errorResponse("User not found.");
        }
        return Response.ok(adminUserRowTemplate.data("u", toRow(target))).build();
    }

    /**
     * Returns the in-place confirm-delete row for a user.
     */
    @GET
    @Path("users/{id}/confirm-delete")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response confirmDeleteUser(@PathParam("id") final UUID id) {
        final User target = User.findById(id);
        if (target == null) {
            return errorResponse("User not found.");
        }
        // Admin delete re-renders the whole list (innerHTML), so the confirmation row's destructive
        // POST targets #admin-users-list; Cancel restores just this row from /admin/users/{id}.
        return Response.ok(confirmDeleteRowTemplate
                .data("rowId", "user-row-" + id)
                .data("cols", 6)
                .data("swatchColour", null)
                .data("label", target.email)
                .data("prompt", "Delete this user, their actions and logs?")
                .data("deleteUrl", "/admin/users/" + id + "/delete")
                .data("deleteTarget", "#admin-users-list")
                .data("deleteSwap", "innerHTML")
                .data("restoreUrl", "/admin/users/" + id)).build();
    }

    /**
     * Changes a user's role, refusing to demote the last administrator.
     */
    @POST
    @Path("users/{id}/role")
    @RolesAllowed("admin")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response changeRole(@PathParam("id") final UUID id, @FormParam("role") final String role) {
        if (!User.ROLE_ADMIN.equals(role) && !User.ROLE_USER.equals(role)) {
            return errorResponse("Invalid role value.");
        }
        final User target = User.findById(id);
        if (target == null) {
            return errorResponse("User not found.");
        }
        if (User.ROLE_USER.equals(role) && isLastAdmin(target)) {
            LOGGER.warn("Admin {} attempted to demote the last administrator {}",
                    identity.getPrincipal().getName(), target.email);
            return errorResponse("Cannot remove the last administrator.");
        }
        target.role = role;
        target.persist();
        LOGGER.info("Admin {} changed role of {} to {}", identity.getPrincipal().getName(), target.email, role);

        final User actor = currentUser();
        return Response.ok(adminUsersListTemplate.data("page", getUsersPage(1, actor.pageSize))).build();
    }

    /**
     * Hard-deletes a user and all their actions/logs, refusing to delete the last administrator.
     */
    @POST
    @Path("users/{id}/delete")
    @RolesAllowed("admin")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response deleteUser(@PathParam("id") final UUID id) {
        final User target = User.findById(id);
        if (target == null) {
            return errorResponse("User not found.");
        }
        if (isLastAdmin(target)) {
            LOGGER.warn("Admin {} attempted to delete the last administrator {}",
                    identity.getPrincipal().getName(), target.email);
            return errorResponse("Cannot delete the last administrator.");
        }

        // Hard-delete in FK order: logs → actions → user
        final List<Action> actions = Action.list("userId", target.id);
        for (final Action a : actions) {
            ActionLog.deleteByAction(target.id, a.id);
        }
        Action.delete("userId", target.id);
        target.delete();
        LOGGER.info("Admin {} deleted user {}", identity.getPrincipal().getName(), target.email);

        final User actor = currentUser();
        return Response.ok(adminUsersListTemplate.data("page", getUsersPage(1, actor.pageSize))).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private record PaginatedUsers(List<UserRow> items, long totalCount, int totalPages, int currentPage) {
    }

    private PaginatedUsers getUsersPage(final int pageNum, final int pageSize) {
        final DateTimeFormatter fmt = formatter();
        final long totalCount = User.count();
        final int totalPages = (int) ((totalCount + pageSize - 1) / pageSize);
        final int actualPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);

        final List<UserRow> items = User.<User>findAll(Sort.by("createdAt"))
                .page(Page.of(actualPage - 1, pageSize))
                .list()
                .stream()
                .map(u -> UserRow.of(u, fmt))
                .toList();

        return new PaginatedUsers(items, totalCount, totalPages, actualPage);
    }

    private UserRow toRow(final User u) {
        return UserRow.of(u, formatter());
    }

    private DateTimeFormatter formatter() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(clock.zone());
    }

    private boolean isLastAdmin(final User target) {
        return User.ROLE_ADMIN.equals(target.role) && User.count("role", User.ROLE_ADMIN) <= 1;
    }

    private User currentUser() {
        return User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
    }

    private Response errorResponse(final String message) {
        // Mirrors templates/partials/banner.html so HTMX error banners match the login/register
        // pages. The `.banner*` styling is defined once in layout.html.
        final String html = "<div class=\"banner banner-error\">" + message + "</div>";
        return Response.status(Response.Status.CONFLICT)
                .entity(html)
                .header("HX-Retarget", "#admin-error")
                .header("HX-Reswap", "innerHTML")
                .build();
    }
}
