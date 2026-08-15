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
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.zodac.diurnal.auth.lockout.IpLockout;
import net.zodac.diurnal.auth.lockout.IpLockoutService;
import net.zodac.diurnal.auth.lockout.IpThrottleConfig;
import net.zodac.diurnal.auth.lockout.IpUnlockResult;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.web.HtmxResponses;

/**
 * The web UI's internal HTMX endpoints for administering the per-IP auth lockout: the paginated history partial and the manual-unlock mutation. The
 * full admin page stays under {@code /admin/users} ({@link AdminWebResource}), which renders the lockout section inline; nothing here is part of the
 * public API (that is {@code /api/v1/admin/ip-lockouts}, {@code AdminIpLockoutsApiResource}). Both surfaces call the same {@link IpLockoutService},
 * so the rules cannot diverge; this resource only translates its outputs into partials/banners.
 *
 * <p>
 * Every endpoint is gated on the lockout being enabled ({@code AUTH_IP_THROTTLE_ENABLED}); when it is disabled the section is not rendered and these
 * endpoints answer {@code 404}.
 */
@Path("/internal/admin/ip-lockouts")
@RolesAllowed(Role.Values.ADMIN_INTERNAL_VALUE)
@RollbackOnErrorStatus
public class AdminIpLockoutsInternalResource {

    private final Template adminIpLockoutsTableTemplate;
    private final Template adminIpLockoutRowTemplate;
    private final Template confirmDeleteRowTemplate;
    private final SecurityIdentity identity;
    private final CurrentUser currentUser;
    private final IpLockoutService ipLockoutService;
    private final IpThrottleConfig ipThrottleConfig;
    private final AppClock clock;

    /**
     * Injects the HTMX partial templates, the security identity, the current-user accessor, the shared lockout service, the throttle settings (for
     * the enabled gate) and the application clock.
     *
     * @param adminIpLockoutsTableTemplate the lockout-table partial template
     * @param adminIpLockoutRowTemplate    the single lockout-row partial template
     * @param confirmDeleteRowTemplate     the shared in-place confirm-row partial template (reused for the unlock confirmation)
     * @param identity                     the calling administrator's security identity
     * @param currentUser                  the current-user accessor
     * @param ipLockoutService             the shared per-IP lockout service
     * @param ipThrottleConfig             the per-IP throttle settings (whether the feature is enabled)
     * @param clock                        the application clock for date-boundary logic
     */
    @Inject
    public AdminIpLockoutsInternalResource(@Location("partials/admin-ip-lockouts-table") final Template adminIpLockoutsTableTemplate,
        @Location("partials/admin-ip-lockout-row") final Template adminIpLockoutRowTemplate,
        @Location("partials/dt-confirm-delete-row") final Template confirmDeleteRowTemplate, final SecurityIdentity identity,
        final CurrentUser currentUser, final IpLockoutService ipLockoutService, final IpThrottleConfig ipThrottleConfig, final AppClock clock) {
        this.adminIpLockoutsTableTemplate = adminIpLockoutsTableTemplate;
        this.adminIpLockoutRowTemplate = adminIpLockoutRowTemplate;
        this.confirmDeleteRowTemplate = confirmDeleteRowTemplate;
        this.identity = identity;
        this.currentUser = currentUser;
        this.ipLockoutService = ipLockoutService;
        this.ipThrottleConfig = ipThrottleConfig;
        this.clock = clock;
    }

    /**
     * Returns just the lockout-table partial (one page of history) for HTMX pagination.
     *
     * @param pageNum the 1-based page to render
     * @return the rendered table partial, or {@code 404} when the lockout feature is disabled
     */
    @GET
    @Path("history")
    @Produces(MediaType.TEXT_HTML)
    public Response history(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        if (!ipThrottleConfig.enabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        final User actor = currentUser.get();
        final ZoneId zone = clock.zoneFor(actor.timezone);
        final Instant now = clock.now();
        final PaginatedIpLockouts history = toHistory(ipLockoutService.history(pageNum, actor.pageSize, now), zone, now);
        return Response.ok(adminIpLockoutsTableTemplate.data("history", history)).build();
    }

    /**
     * Turns a single active lockout row into the shared in-place confirm row (Unlock on the left, Cancel where Unlock sat), mirroring the delete
     * confirmation used elsewhere. Confirming re-renders the whole table; Cancel restores just this row via {@link #row}.
     *
     * @param id the lockout row id
     * @return the rendered confirm row, or a conflict banner when the lockout no longer exists / {@code 404} when the feature is disabled
     */
    @GET
    @Path("{id}/confirm-unlock")
    @Produces(MediaType.TEXT_HTML)
    public Response confirmUnlock(@PathParam("id") final UUID id) {
        if (!ipThrottleConfig.enabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        final IpLockout lockout = ipLockoutService.find(id);
        if (lockout == null) {
            return HtmxResponses.conflictBanner("#admin-error", "That lockout no longer exists.");
        }
        // Unlock re-renders the whole table (innerHTML), so the confirm row's destructive POST targets #ip-lockouts-table; Cancel restores just this
        // row from /internal/admin/ip-lockouts/{id}/row. The unlock itself is keyed by IP (it clears the in-memory enforcement entry for the IP).
        return Response.ok(confirmDeleteRowTemplate
                .data("rowId", "ip-lockout-row-" + id)
                .data("cols", 5)
                .data("swatchColour", null)
                .data("label", lockout.ipAddress)
                .data("prompt", "Unlock this IP so it can log in and register again?")
                .data("confirmLabel", "Unlock")
                .data("deleteUrl", "/internal/admin/ip-lockouts/" + lockout.ipAddress + "/unlock")
                .data("deleteTarget", "#ip-lockouts-table")
                .data("deleteSwap", "innerHTML")
                .data("restoreUrl", "/internal/admin/ip-lockouts/" + id + "/row")).build();
    }

    /**
     * Returns the single plain lockout row (used to restore a row after the unlock confirmation is cancelled).
     *
     * @param id the lockout row id
     * @return the rendered row partial, or a conflict banner when the lockout no longer exists / {@code 404} when the feature is disabled
     */
    @GET
    @Path("{id}/row")
    @Produces(MediaType.TEXT_HTML)
    public Response row(@PathParam("id") final UUID id) {
        if (!ipThrottleConfig.enabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        final IpLockout lockout = ipLockoutService.find(id);
        if (lockout == null) {
            return HtmxResponses.conflictBanner("#admin-error", "That lockout no longer exists.");
        }
        final ZoneId zone = clock.zoneFor(currentUser.get().timezone);
        return Response.ok(adminIpLockoutRowTemplate.data("row", singleRow(lockout, zone, clock.now()))).build();
    }

    /**
     * Manually unlocks a client IP and re-renders the lockout table (the first page, with the just-unlocked row now stamped as unlocked).
     *
     * @param ip the client IP to unlock
     * @return the re-rendered lockout table, or a conflict banner when the IP was not locked
     */
    @POST
    @Path("{ip}/unlock")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response unlock(@PathParam("ip") final String ip) {
        if (!ipThrottleConfig.enabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return switch (ipLockoutService.unlock(identity.getPrincipal().getName(), ip, clock.now())) {
            case final IpUnlockResult.NotLocked ignored -> HtmxResponses.conflictBanner("#admin-error", "That IP is no longer locked out.");
            case final IpUnlockResult.Success ignored -> {
                final User actor = currentUser.get();
                final ZoneId zone = clock.zoneFor(actor.timezone);
                final Instant now = clock.now();
                yield Response.ok(adminIpLockoutsTableTemplate
                        .data("history", toHistory(ipLockoutService.history(1, actor.pageSize, now), zone, now))).build();
            }
        };
    }

    /**
     * Maps a service history page to the template row model, with each row's timestamps rendered in the viewing administrator's timezone and its
     * status derived as of {@code now}. Shared with the full-page render ({@link AdminWebResource}).
     *
     * @param page the page fetched by {@link IpLockoutService#history(int, int, Instant)}
     * @param zone the viewing administrator's timezone
     * @param now  the current instant, for each row's derived status
     * @return the page as rendered history rows
     */
    static PaginatedIpLockouts toHistory(final IpLockoutService.HistoryPage page, final ZoneId zone, final Instant now) {
        final DateTimeFormatter fmt = formatter(zone);
        final String zoneLabel = zone.getId();
        final List<IpLockoutHistoryRow> items = page.rows().stream()
            .map(row -> IpLockoutHistoryRow.of(row, fmt, zoneLabel, now))
            .toList();
        return new PaginatedIpLockouts(items, page.totalCount(), page.totalPages(), page.currentPage());
    }

    private static IpLockoutHistoryRow singleRow(final IpLockout lockout, final ZoneId zone, final Instant now) {
        return IpLockoutHistoryRow.of(lockout, formatter(zone), zone.getId(), now);
    }

    private static DateTimeFormatter formatter(final ZoneId zone) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(zone);
    }

    /**
     * One page of lockout-history rows, as rendered by the history partial and the full page.
     *
     * @param items       the page's history rows
     * @param totalCount  the total number of history rows within the retention window
     * @param totalPages  the page count
     * @param currentPage the rendered (clamped) 1-based page
     */
    record PaginatedIpLockouts(List<IpLockoutHistoryRow> items, long totalCount, int totalPages, int currentPage) {

    }
}
