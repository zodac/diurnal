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

package net.zodac.diurnal.web.admin;

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
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.auth.lockout.IpLockoutService;
import net.zodac.diurnal.auth.lockout.IpThrottleConfig;
import net.zodac.diurnal.auth.session.SessionActivityService;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.AdminUserService;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizes;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * Serves the admin-only full pages: user management and the embedded API documentation. The user-management HTMX partials and mutations live under
 * {@code /internal/admin/users} ({@link AdminUsersInternalResource}).
 */
@Path("/admin")
@RolesAllowed(Role.Values.ADMIN_INTERNAL_VALUE)
public class AdminWebResource {

    private final Template adminUsersTemplate;
    private final Template adminApiDocsTemplate;
    private final CurrentUser currentUser;
    private final AdminUserService adminUserService;
    private final SessionActivityService sessionActivityService;
    private final IpLockoutService ipLockoutService;
    private final IpThrottleConfig ipThrottleConfig;
    private final AppClock clock;

    /**
     * Injects the admin page templates, the current-user accessor, the shared admin-user service, the recently-active presence service, the shared
     * per-IP lockout service, the throttle settings (whether the lockout feature is enabled) and the application clock.
     *
     * @param adminUsersTemplate the admin-users page template
     * @param adminApiDocsTemplate the admin API-docs page template
     * @param currentUser the current-user accessor
     * @param adminUserService the shared admin-user-mutation service
     * @param sessionActivityService the recently-active presence service
     * @param ipLockoutService the shared per-IP lockout service
     * @param ipThrottleConfig the per-IP throttle settings (whether the lockout feature is enabled)
     * @param clock the application clock for date-boundary logic
     */
    @Inject
    public AdminWebResource(@Location("admin-users") final Template adminUsersTemplate,
        @Location("admin-api-docs") final Template adminApiDocsTemplate, final CurrentUser currentUser, final AdminUserService adminUserService,
        final SessionActivityService sessionActivityService, final IpLockoutService ipLockoutService, final IpThrottleConfig ipThrottleConfig,
        final AppClock clock) {
        this.adminUsersTemplate = adminUsersTemplate;
        this.adminApiDocsTemplate = adminApiDocsTemplate;
        this.currentUser = currentUser;
        this.adminUserService = adminUserService;
        this.sessionActivityService = sessionActivityService;
        this.ipLockoutService = ipLockoutService;
        this.ipThrottleConfig = ipThrottleConfig;
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
        final AdminUserService.UsersPage page = adminUserService.usersPage(pageNum, PageSizes.forSection(actor, PageSection.USERS));
        final ZoneId zone = clock.zoneFor(actor.timezone);
        final Instant now = clock.now();
        final List<UUID> ids = page.users().stream().map(u -> u.id).toList();
        final boolean lockoutEnabled = ipThrottleConfig.enabled();
        return adminUsersTemplate
                .data("email", actor.email)
                .data("displayName", actor.displayName)
                .data("theme", actor.theme)
                .data("font", actor.font)
                .data("isAdmin", true)
                .data("page", AdminUsersInternalResource.toRows(page, zone, sessionActivityService.recentActivityByUser(ids, now)))
                .data("ipThrottleEnabled", lockoutEnabled)
                .data("lockoutsHistory", lockoutEnabled
                    ? AdminIpLockoutsInternalResource.toHistory(ipLockoutService.history(1, actor.pageSize, now), zone, now)
                    : new AdminIpLockoutsInternalResource.PaginatedIpLockouts(List.of(), 0L, 0, 1));
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
