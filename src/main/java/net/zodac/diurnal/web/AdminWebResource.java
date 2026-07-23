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
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.update.UpdateAvailability;
import net.zodac.diurnal.update.UpdateCheckService;
import net.zodac.diurnal.update.UpdateStatus;
import net.zodac.diurnal.user.AdminUserService;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * Serves the admin-only full pages: user management and the embedded API documentation. The user-management HTMX partials and mutations live under
 * {@code /internal/admin/users} ({@link AdminUsersInternalResource}).
 */
@Path("/admin")
@RolesAllowed(Role.Values.ADMIN)
public class AdminWebResource {

    @Inject
    @Location("admin-users")
    Template adminUsersTemplate;

    @Inject
    @Location("admin-api-docs")
    Template adminApiDocsTemplate;

    @Inject CurrentUser currentUser;

    @Inject AdminUserService adminUserService;

    @Inject AppClock clock;

    @Inject UpdateCheckService updateCheckService;

    /**
     * Renders the paginated admin users page.
     *
     * @param pageNum the 1-based page to render
     * @return the rendered page
     */
    @GET
    @Path("users")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance usersPage(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User actor = currentUser.get();
        return withUpdateCheck(adminUsersTemplate
                .data("email", actor.email)
                .data("displayName", actor.displayName)
                .data("theme", actor.theme)
                .data("font", actor.font)
                .data("isAdmin", true)
                .data("page", AdminUsersInternalResource.toRows(adminUserService.usersPage(pageNum, actor.pageSize), clock.zoneFor(actor.timezone))));
    }

    /**
     * Renders the embedded API documentation page (Swagger UI in an in-app iframe).
     *
     * @return the rendered page
     */
    @GET
    @Path("api-docs")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance apiDocsPage() {
        final User actor = currentUser.get();
        return withUpdateCheck(adminApiDocsTemplate
                .data("email", actor.email)
                .data("displayName", actor.displayName)
                .data("theme", actor.theme)
                .data("font", actor.font)
                .data("isAdmin", true));
    }

    private TemplateInstance withUpdateCheck(final TemplateInstance template) {
        final UpdateStatus status = updateCheckService.status();
        return template
                .data("updateAvailable", status.availability() == UpdateAvailability.UPDATE_AVAILABLE)
                .data("latestVersion", status.latestVersion())
                .data("latestReleaseUrl", status.latestReleaseUrl());
    }
}
