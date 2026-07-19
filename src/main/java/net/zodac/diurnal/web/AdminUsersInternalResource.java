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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.AdminUserResult;
import net.zodac.diurnal.user.AdminUserService;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * The web UI's internal HTMX endpoints for admin user management: the paginated list partial, single-row partials, and the role-change/delete
 * mutations. The full admin pages stay under {@code /admin} ({@link AdminWebResource}); nothing here is part of the public API (that is
 * {@code /api/v1/*}). Every mutation shares one implementation with the API ({@code AdminUsersApiResource}) — both surfaces call the same
 * {@link AdminUserService} (which owns the last-administrator safeguards), so the rules cannot diverge; this resource only translates
 * {@link AdminUserResult} outcomes into partials/banners.
 */
@Path("/internal/admin/users")
@RolesAllowed(Role.Values.ADMIN)
public class AdminUsersInternalResource {

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

    @Inject CurrentUser currentUser;

    @Inject AdminUserService adminUserService;

    @Inject AppClock clock;

    /**
     * Returns just the users list partial for HTMX pagination.
     *
     * @param pageNum the 1-based page to render
     * @return the rendered list partial
     */
    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance usersList(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User actor = currentUser.get();
        return adminUsersListTemplate.data("page", toRows(adminUserService.usersPage(pageNum, actor.pageSize), actorZone()));
    }

    /**
     * Returns the single table row for one user (used to restore a row after cancel).
     *
     * @param id the user's id
     * @return the rendered row partial
     */
    @GET
    @Path("{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response userRow(@PathParam("id") final UUID id) {
        final User target = adminUserService.find(id);
        if (target == null) {
            return HtmxResponses.conflictBanner("#admin-error", "User not found.");
        }
        return Response.ok(adminUserRowTemplate.data("u", toRow(target, actorZone()))).build();
    }

    /**
     * Returns the in-place confirm-delete row for a user.
     *
     * @param id the user's id
     * @return the rendered confirm-delete row partial
     */
    @GET
    @Path("{id}/confirm-delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response confirmDeleteUser(@PathParam("id") final UUID id) {
        final User target = adminUserService.find(id);
        if (target == null) {
            return HtmxResponses.conflictBanner("#admin-error", "User not found.");
        }
        // Admin delete re-renders the whole list (innerHTML), so the confirmation row's destructive
        // POST targets #admin-users-list; Cancel restores just this row from /internal/admin/users/{id}.
        return Response.ok(confirmDeleteRowTemplate
                .data("rowId", "user-row-" + id)
                .data("cols", 7)
                .data("swatchColour", null)
                .data("label", target.email)
                .data("prompt", "Delete this user, their actions and logs?")
                .data("deleteUrl", "/internal/admin/users/" + id + "/delete")
                .data("deleteTarget", "#admin-users-list")
                .data("deleteSwap", "innerHTML")
                .data("restoreUrl", "/internal/admin/users/" + id)).build();
    }

    /**
     * Changes a user's role, refusing to demote the last administrator.
     *
     * @param id   the user's id
     * @param role the new role's storage value
     * @return the re-rendered row partial, or a conflict banner
     */
    @POST
    @Path("{id}/role")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response changeRole(@PathParam("id") final UUID id, @FormParam("role") final String role) {
        return switch (adminUserService.changeRole(identity.getPrincipal().getName(), id, role)) {
            case AdminUserResult.InvalidRole ignored -> HtmxResponses.conflictBanner("#admin-error", "Invalid role value.");
            case AdminUserResult.NotFound ignored -> HtmxResponses.conflictBanner("#admin-error", "User not found.");
            case AdminUserResult.LastAdmin ignored -> HtmxResponses.conflictBanner("#admin-error", "Cannot remove the last administrator.");
            // Re-render just this row (outerHTML) so the surrounding rows don't repaint — the edited row
            // swaps straight from its edit state to a fresh view state, with no whole-list flash.
            case AdminUserResult.Success success -> Response.ok(adminUserRowTemplate.data("u", toRow(success.user(), actorZone()))).build();
        };
    }

    /**
     * Hard-deletes a user and all their actions/logs, refusing to delete the last administrator.
     *
     * @param id the user's id
     * @return the re-rendered users list partial, or a conflict banner
     */
    @POST
    @Path("{id}/delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response deleteUser(@PathParam("id") final UUID id) {
        return switch (adminUserService.deleteUser(identity.getPrincipal().getName(), id)) {
            case AdminUserResult.NotFound ignored -> HtmxResponses.conflictBanner("#admin-error", "User not found.");
            case AdminUserResult.LastAdmin ignored -> HtmxResponses.conflictBanner("#admin-error", "Cannot delete the last administrator.");
            case AdminUserResult.InvalidRole ignored -> HtmxResponses.conflictBanner("#admin-error", "Invalid role value.");
            case AdminUserResult.Success ignored ->
                Response.ok(adminUsersListTemplate.data("page", toRows(adminUserService.usersPage(1, currentUser.get().pageSize), actorZone())))
                    .build();
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Maps a service {@link AdminUserService.UsersPage} to the template row model, with each row's timestamps rendered in the viewing
     * administrator's zone. Shared with the full-page render ({@link AdminWebResource}).
     *
     * @param page the page fetched by {@link AdminUserService#usersPage(int, int)}
     * @param zone the viewing administrator's timezone
     * @return the page as rendered user rows
     */
    static PaginatedUsers toRows(final AdminUserService.UsersPage page, final ZoneId zone) {
        final DateTimeFormatter fmt = formatter(zone);
        final String zoneLabel = zone.getId();
        final List<UserRow> items = page.users().stream()
            .map(u -> UserRow.of(u, fmt, zoneLabel))
            .toList();
        return new PaginatedUsers(items, page.totalCount(), page.totalPages(), page.currentPage());
    }

    private static UserRow toRow(final User u, final ZoneId zone) {
        return UserRow.of(u, formatter(zone), zone.getId());
    }

    private static DateTimeFormatter formatter(final ZoneId zone) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
    }

    // The timestamps are rendered in the viewing administrator's configured timezone (falling back to
    // the server default when unset), so the admin reads them in their own local time rather than the
    // server's; the zone id is surfaced as a tooltip on each date cell.
    private ZoneId actorZone() {
        return clock.zoneFor(currentUser.get().timezone);
    }

    /**
     * One page of user rows, as rendered by the admin users list partial and the full page.
     *
     * @param items       the page's user rows
     * @param totalCount  the total number of users
     * @param totalPages  the page count
     * @param currentPage the rendered (clamped) 1-based page
     */
    record PaginatedUsers(List<UserRow> items, long totalCount, int totalPages, int currentPage) {

    }
}
