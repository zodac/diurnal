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

package net.zodac.diurnal.auth.lockout;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
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
 * The public REST API for administering the single global per-IP auth lockout: list the IPs locked out right now, read the last week of lockout
 * history, and manually unlock an IP. Requires an administrator's Bearer session token. Every operation shares one implementation with the admin page
 * ({@code AdminIpLockoutsInternalResource}) — both call the same {@link IpLockoutService} — so the rules cannot diverge; this resource only
 * translates the service outputs into JSON.
 *
 * <p>
 * The whole feature is gated on the lockout being enabled ({@code AUTH_IP_THROTTLE_ENABLED}); when it is disabled every operation here answers
 * {@code 404}, exactly as registration does when password auth is off — there is no lockout state to administer.
 */
@Tag(name = "Admin", description = "Administrator-only management of the per-IP auth lockout: list locked IPs, read lockout history, unlock an IP.")
@Path("/api/v1/admin/ip-lockouts")
@RolesAllowed(Role.Values.ADMIN_INTERNAL_VALUE)
@Produces(MediaType.APPLICATION_JSON)
@RollbackOnErrorStatus
public class AdminIpLockoutsApiResource {

    private static final String EXAMPLE_IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - documentation example IP for the OpenAPI schema

    private final IpLockoutService ipLockoutService;
    private final IpThrottleConfig ipThrottleConfig;
    private final CurrentUser currentUser;
    private final SecurityIdentity identity;
    private final AppClock clock;

    /**
     * Injects the shared lockout service, the throttle settings (for the enabled gate), the current-user accessor, the security identity and the
     * application clock.
     *
     * @param ipLockoutService the shared per-IP lockout service
     * @param ipThrottleConfig the per-IP throttle settings (whether the feature is enabled)
     * @param currentUser      the current-user accessor (for the page-size preference)
     * @param identity         the calling administrator's security identity
     * @param clock            the application clock
     */
    @Inject
    public AdminIpLockoutsApiResource(final IpLockoutService ipLockoutService, final IpThrottleConfig ipThrottleConfig,
        final CurrentUser currentUser, final SecurityIdentity identity, final AppClock clock) {
        this.ipLockoutService = ipLockoutService;
        this.ipThrottleConfig = ipThrottleConfig;
        this.currentUser = currentUser;
        this.identity = identity;
        this.clock = clock;
    }

    /**
     * Lists the client IPs currently locked out.
     *
     * @return the currently-locked IPs, or {@code 404} when the lockout feature is disabled
     */
    @GET
    @Operation(
        summary = "List currently locked-out IPs",
        description = "Returns every client IP that is locked out of login and registration right now, with each lockout's expiry and failure count. "
        + "Available only when the per-IP lockout is enabled.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The currently locked-out IPs.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = CurrentLockoutsDto.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "403", description = "The caller is not an administrator."),
        @APIResponse(responseCode = "404", description = "The per-IP lockout feature is disabled.")
    })
    public Response listCurrent() {
        if (!ipThrottleConfig.enabled()) {
            return featureDisabled();
        }
        final List<CurrentLockoutDto> items = ipLockoutService.currentLockouts(clock.now()).stream()
            .map(CurrentLockoutDto::from)
            .toList();
        return Response.ok(new CurrentLockoutsDto(items)).build();
    }

    /**
     * Lists one page of the lockout history within the retention window (the last week), most recent first.
     *
     * @param pageNum the 1-based page to return
     * @return the requested page of history, or {@code 404} when the lockout feature is disabled
     */
    @GET
    @Path("history")
    @Operation(
        summary = "List IP lockout history",
        description = "Returns one page of the per-IP lockout history from the last week, most recent first, including lockouts that have since "
        + "expired or been manually unlocked. The page size is the calling administrator's 'items per page' preference; an out-of-range page is "
        + "rejected with a 400. Available only when the per-IP lockout is enabled.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The requested page of lockout history.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = IpLockoutHistoryPageDto.class))),
        @APIResponse(responseCode = "400", description = "The requested page is out of range.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "403", description = "The caller is not an administrator."),
        @APIResponse(responseCode = "404", description = "The per-IP lockout feature is disabled.")
    })
    public Response listHistory(
        @Parameter(name = "page", in = ParameterIn.QUERY, description = "The 1-based page to return (default 1); out-of-range values are rejected.")
        @QueryParam("page") @DefaultValue("1") final int pageNum) {
        if (!ipThrottleConfig.enabled()) {
            return featureDisabled();
        }
        final Instant now = clock.now();
        final IpLockoutService.HistoryPage page = ipLockoutService.history(pageNum, currentUser.get().pageSize, now);
        // Surface input policy: the API rejects an out-of-range page (the web UI clamps it into range) so a page number is never silently changed.
        if (pageNum < 1 || pageNum > Math.max(1, page.totalPages())) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse("Page " + pageNum + " is out of range"))
                .build();
        }
        return Response.ok(IpLockoutHistoryPageDto.from(page, now)).build();
    }

    /**
     * Manually unlocks a client IP.
     *
     * @param ip the client IP to unlock
     * @return {@code 204} on success, or {@code 404} when the feature is disabled or the IP was not locked
     */
    @DELETE
    @Path("{ip}")
    @Transactional
    @Operation(
        summary = "Unlock an IP",
        description = "Clears the lockout for the given client IP so login and registration from it are accepted again, and stamps the matching "
        + "history record as manually unlocked. Available only when the per-IP lockout is enabled.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "The IP was unlocked."),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "403", description = "The caller is not an administrator."),
        @APIResponse(responseCode = "404", description = "The feature is disabled, or the IP was not currently locked out.")
    })
    public Response unlock(
        @Parameter(name = "ip", in = ParameterIn.PATH, required = true, description = "The client IP address to unlock.")
        @PathParam("ip") final String ip) {
        if (!ipThrottleConfig.enabled()) {
            return featureDisabled();
        }
        return switch (ipLockoutService.unlock(identity.getPrincipal().getName(), ip, clock.now())) {
            case final IpUnlockResult.NotLocked ignored -> Response.status(Response.Status.NOT_FOUND).build();
            case final IpUnlockResult.Success ignored -> Response.noContent().build();
        };
    }

    private static Response featureDisabled() {
        return Response.status(Response.Status.NOT_FOUND)
            .entity(new ApiErrorResponse("The per-IP lockout feature is disabled"))
            .build();
    }

    /**
     * One IP that is locked out right now.
     *
     * @param ipAddress    the locked-out client IP
     * @param lockedUntil  when the lockout expires (ISO-8601 instant)
     * @param failureCount the failure tally recorded for the IP
     */
    @Schema(description = "A client IP that is currently locked out.")
    record CurrentLockoutDto(
        @Schema(examples = EXAMPLE_IP, description = "The locked-out client IP address.") String ipAddress,
        @Schema(examples = "2026-06-15T07:45:00Z", description = "When the lockout expires (ISO-8601 instant).") Instant lockedUntil,
        @Schema(examples = "15", description = "The failure count recorded for the IP when it was locked.") int failureCount) {

        private static CurrentLockoutDto from(final AttemptThrottle.ActiveLockout lockout) {
            return new CurrentLockoutDto(lockout.key(), lockout.lockedUntil(), lockout.failureCount());
        }
    }

    /**
     * The currently locked-out IPs.
     *
     * @param items the locked-out IPs
     */
    @Schema(description = "The client IPs currently locked out.")
    record CurrentLockoutsDto(@Schema(description = "The currently locked-out IPs.") List<CurrentLockoutDto> items) {

    }

    /**
     * One lockout history record.
     *
     * @param ipAddress    the client IP that was locked out
     * @param lockedAt     when the lockout tripped (ISO-8601 instant)
     * @param lockedUntil  when the lockout would naturally expire (ISO-8601 instant)
     * @param failureCount the failure tally recorded when it tripped
     * @param status       the derived status: {@code ACTIVE}, {@code EXPIRED} or {@code UNLOCKED}
     * @param unlockedAt   when an administrator manually unlocked the IP (ISO-8601 instant), or {@code null}
     * @param unlockedBy   the email of the administrator who unlocked the IP, or {@code null}
     */
    @Schema(description = "A single historical per-IP lockout.")
    record IpLockoutHistoryDto(
        @Schema(examples = EXAMPLE_IP, description = "The client IP that was locked out.") String ipAddress,
        @Schema(examples = "2026-06-15T07:30:00Z", description = "When the lockout tripped (ISO-8601 instant).") Instant lockedAt,
        @Schema(examples = "2026-06-15T07:45:00Z", description = "When the lockout would naturally expire (ISO-8601 instant).") Instant lockedUntil,
        @Schema(examples = "15", description = "The failure count recorded when the lockout tripped.") int failureCount,
        @Schema(examples = "EXPIRED", description = "The derived status: ACTIVE, EXPIRED or UNLOCKED.") String status,
        @Schema(examples = "2026-06-15T07:40:00Z", description = "When an administrator manually unlocked the IP (ISO-8601 instant); null otherwise.")
        @Nullable Instant unlockedAt,
        @Schema(examples = "admin@example.com", description = "The administrator who manually unlocked the IP; null otherwise.")
        @Nullable String unlockedBy) {

        /**
         * Maps a history entity to its API representation, deriving the status as of {@code now}.
         *
         * @param lockout the history entity
         * @param now     the current instant, for the derived status
         * @return the DTO
         */
        static IpLockoutHistoryDto from(final IpLockout lockout, final Instant now) {
            return new IpLockoutHistoryDto(lockout.ipAddress, lockout.lockedAt, lockout.lockedUntil, lockout.failureCount,
                IpLockoutStatus.of(lockout, now).name(), lockout.unlockedAt, lockout.unlockedBy);
        }
    }

    /**
     * One page of lockout history.
     *
     * @param items       the page's history records
     * @param totalCount  the total number of records within the retention window
     * @param totalPages  the page count
     * @param currentPage the returned (clamped) 1-based page
     */
    @Schema(description = "One page of per-IP lockout history, most recent first.")
    record IpLockoutHistoryPageDto(
        @Schema(description = "The page's history records, most recent first.") List<IpLockoutHistoryDto> items,
        @Schema(examples = "42", description = "The total number of history records within the retention window.") long totalCount,
        @Schema(examples = "5", description = "The total number of pages.") int totalPages,
        @Schema(examples = "1", description = "The returned (clamped) 1-based page.") int currentPage) {

        /**
         * Maps a service history page to its API representation.
         *
         * @param page the fetched page
         * @param now  the current instant, for each record's derived status
         * @return the DTO
         */
        private static IpLockoutHistoryPageDto from(final IpLockoutService.HistoryPage page, final Instant now) {
            return new IpLockoutHistoryPageDto(
                page.rows().stream().map(row -> IpLockoutHistoryDto.from(row, now)).toList(),
                page.totalCount(),
                page.totalPages(),
                page.currentPage());
        }
    }
}
