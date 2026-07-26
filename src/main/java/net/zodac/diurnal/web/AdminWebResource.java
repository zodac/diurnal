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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.zodac.diurnal.time.AppClock;
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

    private final Template adminUsersTemplate;
    private final Template adminApiDocsTemplate;
    private final CurrentUser currentUser;
    private final AdminUserService adminUserService;
    private final AppClock clock;

    /**
     * Injects the admin page templates, the current-user accessor, the shared admin-user service and the application clock.
     *
     * @param adminUsersTemplate the admin-users page template
     * @param adminApiDocsTemplate the admin API-docs page template
     * @param currentUser the current-user accessor
     * @param adminUserService the shared admin-user-mutation service
     * @param clock the application clock for date-boundary logic
     */
    @Inject
    public AdminWebResource(@Location("admin-users") final Template adminUsersTemplate,
        @Location("admin-api-docs") final Template adminApiDocsTemplate, final CurrentUser currentUser, final AdminUserService adminUserService,
        final AppClock clock) {
        this.adminUsersTemplate = adminUsersTemplate;
        this.adminApiDocsTemplate = adminApiDocsTemplate;
        this.currentUser = currentUser;
        this.adminUserService = adminUserService;
        this.clock = clock;
    }

    /**
     * Renders the paginated admin users page.
     *
     * @param pageNum the 1-based page to render
     * @return the rendered page
     */
    @GET
    @Path("users")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance usersPage(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User actor = currentUser.get();
        return adminUsersTemplate
                .data("email", actor.email)
                .data("displayName", actor.displayName)
                .data("theme", actor.theme)
                .data("font", actor.font)
                .data("isAdmin", true)
                .data("page", AdminUsersInternalResource.toRows(adminUserService.usersPage(pageNum, actor.pageSize), clock.zoneFor(actor.timezone)));
    }

    /**
     * Renders the embedded API documentation page (Swagger UI in an in-app iframe).
     *
     * @return the rendered page
     */
    @GET
    @Path("api-docs")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance apiDocsPage() {
        final User actor = currentUser.get();
        return adminApiDocsTemplate
                .data("email", actor.email)
                .data("displayName", actor.displayName)
                .data("theme", actor.theme)
                .data("font", actor.font)
                .data("isAdmin", true);
    }
}
