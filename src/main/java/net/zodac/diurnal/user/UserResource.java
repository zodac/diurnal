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

package net.zodac.diurnal.user;

import io.quarkus.security.Authenticated;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import net.zodac.diurnal.auth.ClientAddress;
import net.zodac.diurnal.auth.PasswordChangeResult;
import net.zodac.diurnal.auth.PasswordChangeService;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * REST API endpoints for the authenticated user's own account: read the profile, update the display name and preferences, and change the password.
 * Every mutation shares one implementation with the Settings page — both surfaces call the same {@link ProfileService}/{@code PasswordChangeService},
 * so the rules cannot diverge; this resource only translates the sealed results into JSON.
 */
@Tag(name = "Users", description = "The authenticated user's profile, preferences and password.")
@Path("/api/v1/users")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    private static final String BEARER_PREFIX = "Bearer ";

    @Inject
    CurrentUser currentUser;

    @Inject
    ProfileService profileService;

    @Inject
    PasswordChangeService passwordChangeService;

    @Context
    @Nullable
    RoutingContext routingContext;

    /**
     * Returns the current user as a {@link UserDto} ({@code 200}), or {@code 404} if not found.
     */
    @GET
    @Path("/me")
    @Operation(
        summary = "Get the current user",
        description = "Returns the authenticated user's profile: id, email, display name, role, and a "
        + "nested preferences object (theme, font, pageSize, showStatsSummary, decimalPlaces, "
        + "calendarView, statsFields, timezone)."
    )
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The authenticated user's profile.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = UserDto.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "The authenticated account no longer exists.")
    })
    public Response me() {
        // CurrentUser resolves the account from the SecurityIdentity built by session auth
        // (UserIdentities.of): the userId attribute is preferred, with the principal email as the
        // fallback. The Bearer credential is an opaque session token, not a JWT — there is no token
        // subject to read.
        return currentUser.find()
                .map(u -> Response.ok(UserDto.from(u)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * Partially updates the current user's display name and/or preferences: only the fields present in the body change (PATCH semantics). Every
     * submitted value is validated and an unrecognised one is rejected with a {@code 400} (never silently coerced to a default) — the display name,
     * enum-backed preferences, timezone, page size and decimal places alike; the one deliberate exception is a blank timezone, the explicit
     * "follow the server default" reset.
     *
     * @param request the fields to change
     * @return the updated profile
     */
    @PATCH
    @Path("/me")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(
        summary = "Update the current user",
        description = "Partially updates the display name and/or preferences: absent fields keep their current value, and any submitted value that "
        + "is not recognised is rejected with a 400 naming the allowed values — nothing is ever silently changed. A blank timezone explicitly "
        + "resets it to the server default.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The updated profile.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = UserDto.class))),
        @APIResponse(responseCode = "400", description = "A submitted value is invalid; the message names the allowed values.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    })
    public Response updateMe(final @Nullable UpdateMeRequest request) {
        final User user = currentUser.get();
        if (request != null) {
            final ProfileResult result = applyUpdates(user, request);
            if (result instanceof final ProfileResult.Invalid invalid) {
                return Response.status(Response.Status.BAD_REQUEST).entity(new ApiErrorResponse(invalid.message())).build();
            }
        }
        return Response.ok(UserDto.from(user)).build();
    }

    /**
     * Changes the current user's password after proving knowledge of the existing one, then revokes every <em>other</em> session (web and API alike)
     * while keeping the calling token signed in.
     *
     * @param request         the current and new passwords
     * @param authorization   the Bearer header, so the calling session survives the revocation
     * @param sessionCookie   the session cookie, when the caller authenticates with one instead
     * @return {@code 204} on success
     */
    @PUT
    @Path("/me/password")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(
        summary = "Change the current user's password",
        description = "Changes the password after verifying the current one, then revokes every OTHER session for the account (web and API alike); "
        + "the calling token stays signed in. Rejected for OIDC-only accounts and deployments without password authentication.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "The password was changed and every other session revoked."),
        @APIResponse(responseCode = "400", description = "The current password is incorrect, or the new password is missing or too long.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "403", description = "The account is OIDC-only, or password authentication is disabled.")
    })
    public Response changePassword(
        final @Nullable ChangePasswordRequest request,
        @Parameter(hidden = true) @HeaderParam("Authorization") @Nullable final String authorization,
        @Parameter(hidden = true) @CookieParam("diurnal_session") @Nullable final String sessionCookie) {
        final String currentPassword = request == null ? null : request.currentPassword();
        final String newPassword = request == null ? null : request.newPassword();
        // No confirmPassword: an API client confirms the new password on its own side.
        final PasswordChangeResult result = passwordChangeService.change(currentUser.get(), currentPassword, newPassword,
            null, callingToken(authorization, sessionCookie), ClientAddress.of(routingContext));
        return switch (result) {
            case PasswordChangeResult.Success ignored -> Response.noContent().build();
            case PasswordChangeResult.NotLocalAccount ignored -> Response.status(Response.Status.FORBIDDEN).build();
            case PasswordChangeResult.WrongCurrentPassword ignored -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiErrorResponse(PasswordChangeService.CURRENT_PASSWORD_ERROR))
                    .build();
            case PasswordChangeResult.InvalidNewPassword invalid -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiErrorResponse(invalid.message()))
                    .build();
        };
    }

    // Applies each present field in a fixed order, stopping at the first rejection; every rule lives in
    // the shared ProfileService.
    private ProfileResult applyUpdates(final User user, final UpdateMeRequest request) {
        ProfileResult result = new ProfileResult.Updated();
        if (request.displayName() != null) {
            result = profileService.updateDisplayName(user, request.displayName());
        }
        final PreferencesUpdate preferences = request.preferences();
        if (preferences == null || result instanceof ProfileResult.Invalid) {
            return result;
        }
        if (preferences.theme() != null) {
            result = profileService.updateTheme(user, preferences.theme());
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        if (preferences.font() != null) {
            result = profileService.updateFont(user, preferences.font());
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        if (preferences.calendarView() != null) {
            result = profileService.updateCalendarView(user, preferences.calendarView());
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        if (preferences.timezone() != null) {
            result = profileService.updateTimezone(user, preferences.timezone());
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        if (preferences.pageSize() != null) {
            result = profileService.updatePageSize(user, Integer.toString(preferences.pageSize()));
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        if (preferences.decimalPlaces() != null) {
            result = profileService.updateDecimalPlaces(user, Integer.toString(preferences.decimalPlaces()));
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        if (preferences.showStatsSummary() != null) {
            result = profileService.updateShowStatsSummary(user, preferences.showStatsSummary());
        }
        final List<StatFieldPref> statsFields = preferences.statsFields();
        if (statsFields != null) {
            final List<String> order = statsFields.stream().map(StatFieldPref::key).toList();
            final List<String> enabled = statsFields.stream().filter(StatFieldPref::enabled).map(StatFieldPref::key).toList();
            result = profileService.updateStatsFields(user, order, enabled);
        }
        return result;
    }

    private static @Nullable String callingToken(final @Nullable String authorization, final @Nullable String sessionCookie) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length()).strip();
        }
        return sessionCookie;
    }

    /**
     * The body for partially updating the current user.
     *
     * @param displayName the new display name, or {@code null} to keep the current one
     * @param preferences the preference fields to change, or {@code null} to change none
     */
    @Schema(description = "Fields for partially updating the current user; absent fields keep their current value.")
    public record UpdateMeRequest(
        @Schema(examples = "Ada Lovelace", description = "The new display name; 2-100 characters.") @Nullable String displayName,
        @Schema(description = "The preference fields to change; absent fields keep their current value.")
        @Nullable PreferencesUpdate preferences) {
    }

    /**
     * The preference fields of a partial profile update; each mirrors the identically named field of {@code UserDto.Preferences}, and
     * {@code UserPreferencesExposureTest} keeps the two in sync with the entity.
     *
     * @param theme            the UI colour scheme; unrecognised values are rejected
     * @param font             the UI font family; unrecognised values are rejected
     * @param calendarView     the dashboard calendar layout; unrecognised values are rejected
     * @param timezone         the IANA timezone override; blank resets to the server default, unrecognised values are rejected
     * @param pageSize         the rows per page in list views; rejected when out of range
     * @param decimalPlaces    the decimal places for fractional stats; rejected when out of range
     * @param showStatsSummary whether the dashboard renders the stats-summary strip
     * @param statsFields      the full ordered "Action stats" arrangement (key + enabled per stat)
     */
    @Schema(description = "Preference fields for a partial update; absent fields keep their current value.")
    public record PreferencesUpdate(
        @Schema(examples = "system", description = "The UI colour scheme: 'system', 'light' or 'dark'; anything else is rejected.")
        @Nullable String theme,
        @Schema(examples = "nova", description = "The UI font family: 'nova', 'standard' or 'dyslexic'; anything else is rejected.")
        @Nullable String font,
        @Schema(examples = "full", description = "Dashboard calendar layout: 'full', 'minimal' or 'stacked'; anything else is rejected.")
        @Nullable String calendarView,
        @Schema(examples = "Europe/London",
        description = "IANA timezone override from the offered options; blank resets to the server default, anything else is rejected.")
        @Nullable String timezone,
        @Schema(examples = "25", description = "Number of rows displayed per page in list views (1-100); rejected when out of range.")
        @Nullable Integer pageSize,
        @Schema(examples = "1", description = "Number of decimal places used to render fractional stats (0-5); rejected when out of range.")
        @Nullable Integer decimalPlaces,
        @Schema(examples = "true", description = "Whether the dashboard renders the per-action stats-summary strip.")
        @Nullable Boolean showStatsSummary,
        @Schema(description = "The full ordered 'Action stats' arrangement (key + enabled per stat); unknown keys are ignored.")
        @Nullable List<StatFieldPref> statsFields) {
    }

    /**
     * The body for changing the current user's password.
     *
     * @param currentPassword the existing password, as proof of ownership
     * @param newPassword     the new password
     */
    @Schema(description = "The current password (as proof of ownership) and the new password to set.")
    public record ChangePasswordRequest(
        @Schema(examples = "correct horse battery staple", description = "The existing password, as proof of account ownership.")
        @Nullable String currentPassword,
        @Schema(examples = "correct horse battery staple 2", description = "The new password; at most 128 characters.")
        @Nullable String newPassword) {
    }
}
