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
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.util.List;
import net.zodac.diurnal.auth.PasswordChangeResult;
import net.zodac.diurnal.auth.PasswordChangeService;
import net.zodac.diurnal.http.ClientAddress;
import net.zodac.diurnal.http.EntityTags;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.stats.StatField;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
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
@RollbackOnErrorStatus
public class UserResource {

    private static final String BEARER_PREFIX = "Bearer ";

    private final CurrentUser currentUser;
    private final ProfileService profileService;
    private final PasswordChangeService passwordChangeService;

    /**
     * Injects the current-user accessor and the shared profile and password-change services.
     *
     * @param currentUser the current-user accessor
     * @param profileService the shared profile-mutation service
     * @param passwordChangeService the shared password-change service
     */
    @Inject
    public UserResource(final CurrentUser currentUser, final ProfileService profileService, final PasswordChangeService passwordChangeService) {
        this.currentUser = currentUser;
        this.profileService = profileService;
        this.passwordChangeService = passwordChangeService;
    }

    /**
     * Returns the current user as a {@link UserDto} ({@code 200}), {@code 304} when unchanged since the caller's ETag, or {@code 404} if not found.
     *
     * @param request the JAX-RS request, used to evaluate the {@code If-None-Match} conditional against the profile's ETag
     * @return the authenticated user's profile, an empty {@code 304} response, or {@code 404}
     */
    @GET
    @Path("/me")
    @Operation(
        summary = "Get the current user",
        description = "Returns the authenticated user's profile: ID, email, display name, role, and a "
        + "nested preferences object (theme, font, language, pageSize, showStatsSummary, decimalPlaces, "
        + "calendarView, statsFields, timezone, weekStart)."
    )
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The authenticated user's profile.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = UserDto.class)))
    @APIResponse(responseCode = "304", description = "Not modified: the profile is unchanged since the ETag in the 'If-None-Match' request "
        + "header, so no body is returned.")
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    @APIResponse(responseCode = "404", description = "The authenticated account no longer exists.")
    public Response me(@Context final Request request) {
        // CurrentUser resolves the account from the SecurityIdentity built by session auth
        // (UserIdentities.of): the userId attribute is preferred, with the principal email as the
        // fallback. The Bearer credential is an opaque session token, not a JWT — there is no token
        // subject to read.
        return currentUser.find()
                .map(u -> conditionalUser(u, request))
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }

    private static Response conditionalUser(final User user, final Request request) {
        // The user row is already loaded, so its updatedAt (bumped on every profile/preference/role change) is a
        // free, exact validator — the conditional just skips building and serialising the DTO.
        final EntityTag tag = EntityTags.weak(user.id, user.updatedAt.toEpochMilli());
        final Response.ResponseBuilder notModified = request.evaluatePreconditions(tag);
        if (notModified != null) {
            return EntityTags.withPrivateValidator(notModified, tag).build();
        }
        return EntityTags.withPrivateValidator(Response.ok(UserDto.from(user)), tag).build();
    }

    /**
     * Partially updates the current user's display name and/or preferences: only the fields present in the body change (PATCH semantics). Every
     * submitted value is validated and an unrecognised one is rejected with a {@code 400} (never silently coerced to a default) — the display name,
     * enum-backed preferences, timezone, page size and decimal places alike; the two deliberate exceptions are a blank timezone and a blank week
     * start, the explicit "follow the server default"/"follow the account's language" resets.
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
        + "resets it to the server default, and a blank weekStart resets it to the account's language.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The updated profile.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = UserDto.class)))
    @APIResponse(responseCode = "400", description = "A submitted value is invalid; the message names the allowed values.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    public Response updateMe(final @Nullable UpdateMeRequest request) {
        final User user = currentUser.get();
        if (request != null) {
            final ProfileResult result = applyUpdates(user, request);
            if (result instanceof ProfileResult.Invalid(final ProfileRejection rejection)) {
                // A rejected field leaves any field applied before it mutated on the managed entity; the class-level @RollbackOnErrorStatus rolls
                // the whole transaction back on this 400, so a rejected request never silently persists part of a mutation.
                return Response.status(Response.Status.BAD_REQUEST).entity(new ApiErrorResponse(ProfileService.message(rejection))).build();
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
    @Operation(
        summary = "Change the current user's password",
        description = "Changes the password after verifying the current one, then revokes every OTHER session for the account (web and API alike); "
        + "the calling token stays signed in. Rejected for accounts holding no password (OIDC-only accounts) — but works regardless of whether "
        + "password LOGIN is enabled, so a break-glass administrator can maintain its credential.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "204", description = "The password was changed and every other session revoked.")
    @APIResponse(responseCode = "400",
        description = "The current password is incorrect, or the new password is missing, too long, or the same as the existing one.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    @APIResponse(responseCode = "403", description = "The account holds no password to change (OIDC-only sign-in).")
    public Response changePassword(
        final @Nullable ChangePasswordRequest request,
        @Parameter(hidden = true) @HeaderParam("Authorization") @Nullable final String authorization,
        @Parameter(hidden = true) @CookieParam("diurnal_session") @Nullable final String sessionCookie,
        @Context final RoutingContext routingContext) {
        final String currentPassword = request == null ? null : request.currentPassword();
        final String newPassword = request == null ? null : request.newPassword();
        // No confirmPassword: an API client confirms the new password on its own side.
        final PasswordChangeResult result = passwordChangeService.change(currentUser.get(), currentPassword, newPassword,
            null, callingToken(authorization, sessionCookie), ClientAddress.of(routingContext));
        return switch (result) {
            case final PasswordChangeResult.Success _ -> Response.noContent().build();
            case final PasswordChangeResult.NotLocalAccount _ -> Response.status(Response.Status.FORBIDDEN).build();
            case final PasswordChangeResult.WrongCurrentPassword _ -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiErrorResponse(PasswordChangeService.CURRENT_PASSWORD_ERROR))
                    .build();
            case final PasswordChangeResult.InvalidNewPassword invalid -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ApiErrorResponse(PasswordChangeService.message(invalid.reason())))
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
        result = applyAppearance(user, preferences);
        if (result instanceof ProfileResult.Invalid) {
            return result;
        }
        result = applyPaging(user, preferences);
        if (result instanceof ProfileResult.Invalid) {
            return result;
        }
        return applyStatsPreferences(user, preferences, result);
    }

    // Each group stops at its first rejection, so the message reported is always the first field the request got wrong.
    private ProfileResult applyAppearance(final User user, final PreferencesUpdate preferences) {
        ProfileResult result = new ProfileResult.Updated();
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
        if (preferences.language() != null) {
            result = profileService.updateLanguage(user, preferences.language());
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
        if (preferences.noteColour() != null) {
            result = profileService.updateNoteColour(user, preferences.noteColour());
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        // No Invalid branch: a boolean has no value to reject, exactly as showStatsSummary has none.
        if (preferences.showNoteCounter() != null) {
            result = profileService.updateShowNoteCounter(user, preferences.showNoteCounter());
        }
        if (preferences.timezone() != null) {
            result = profileService.updateTimezone(user, preferences.timezone());
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        if (preferences.weekStart() != null) {
            result = profileService.updateWeekStart(user, preferences.weekStart());
        }
        return result;
    }

    private ProfileResult applyPaging(final User user, final PreferencesUpdate preferences) {
        ProfileResult result = new ProfileResult.Updated();
        if (preferences.pageSize() != null) {
            result = profileService.updatePageSize(user, Integer.toString(preferences.pageSize()));
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        // The whole override set, like statsFields: the resource pairs its own JSON shape into the two lists the shared service takes (the settings
        // form posts the same pair), and the service holds every rule. An empty array clears every override.
        final List<PageSizePref> pageSizes = preferences.pageSizes();
        if (pageSizes != null) {
            final List<String> sections = pageSizes.stream().map(PageSizePref::section).toList();
            final List<String> values = pageSizes.stream().map(pref -> Integer.toString(pref.pageSize())).toList();
            result = profileService.updatePageSizes(user, sections, values);
            if (result instanceof ProfileResult.Invalid) {
                return result;
            }
        }
        if (preferences.decimalPlaces() != null) {
            result = profileService.updateDecimalPlaces(user, Integer.toString(preferences.decimalPlaces()));
        }
        return result;
    }

    private ProfileResult applyStatsPreferences(final User user, final PreferencesUpdate preferences, final ProfileResult current) {
        ProfileResult result = current;
        if (preferences.showStatsSummary() != null) {
            result = profileService.updateShowStatsSummary(user, preferences.showStatsSummary());
        }
        final List<StatFieldPref> statsFields = preferences.statsFields();
        if (statsFields != null) {
            final List<String> order = statsFields.stream().map(StatFieldPref::key).toList();
            final List<String> enabled = statsFields.stream().filter(StatFieldPref::enabled).map(StatFieldPref::key).toList();
            final List<String> labels = statsFields.stream().map(pref -> pref.label() == null ? "" : pref.label()).toList();
            result = profileService.updateStatsFields(user, order, enabled, StatField.labelsByKey(order, labels));
        }
        return result;
    }

    @Nullable
    private static String callingToken(final @Nullable String authorization, final @Nullable String sessionCookie) {
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
    // Public is forced: Quarkus's generated (de)serializer is not a nestmate so private throws IllegalAccessError, and the endpoint
    // taking it must be public for JAX-RS, so package-private would trip ClassEscapesItsScope instead.
    @Schema(description = "Fields for partially updating the current user; absent fields keep their current value.")
    @SuppressWarnings({"unused", "WeakerAccess"}) // JSON request body: the canonical constructor is invoked reflectively by Jackson, never from Java
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
     * @param language         the UI language; unrecognised values are rejected
     * @param calendarView     the dashboard calendar layout; unrecognised values are rejected
     * @param noteColour       the {@code #rrggbb} colour the user's day notes are shown in; anything else is rejected
     * @param showNoteCounter  whether the dashboard note box shows its character counter
     * @param timezone         the IANA timezone override; blank resets to the server default, unrecognised values are rejected
     * @param weekStart        the day the calendar's week starts on; blank resets to the account's language, unrecognised values are rejected
     * @param pageSize         the rows per page in list views; rejected when out of range
     * @param pageSizes        the FULL set of per-section page-size overrides; a section left out follows {@code pageSize}
     * @param decimalPlaces    the decimal places for fractional stats; rejected when out of range
     * @param showStatsSummary whether the dashboard renders the stats-summary strip
     * @param statsFields      the full ordered "Action stats" arrangement (key + enabled + optional custom name per stat)
     */
    @Schema(description = "Preference fields for a partial update; absent fields keep their current value.")
    public record PreferencesUpdate(
        @Schema(examples = "system", description = "The UI colour scheme: 'system', 'light' or 'dark'; anything else is rejected.")
        @Nullable String theme,
        @Schema(examples = "nova", description = "The UI font family: 'nova', 'standard' or 'dyslexic'; anything else is rejected.")
        @Nullable String font,
        @Schema(examples = "en-GB",
        description = "The UI language, as a BCP-47 tag matching one of the currently offered languages; anything else is rejected.")
        @Nullable String language,
        @Schema(examples = "full", description = "Dashboard calendar layout: 'full', 'minimal' or 'stacked'; anything else is rejected.")
        @Nullable String calendarView,
        @Schema(examples = UserSettings.DEFAULT_NOTE_COLOUR,
        description = "The colour the user's day notes are shown in, as a '#rrggbb' hex value; anything else is rejected.")
        @Nullable String noteColour,
        @Schema(examples = "true", description = "Whether the dashboard note box shows its character counter. Display-only: the length bound is "
        + "unchanged either way, and the counter still appears while a note is over it.")
        @Nullable Boolean showNoteCounter,
        @Schema(examples = "Europe/London",
        description = "IANA timezone override from the offered options; blank resets to the server default, anything else is rejected.")
        @Nullable String timezone,
        @Schema(examples = "monday",
        description = "The day the dashboard calendar's week starts on ('monday' through 'sunday'); blank resets to the account's language, "
        + "anything else is rejected.")
        @Nullable String weekStart,
        @Schema(examples = "25", description = "Number of rows displayed per page in list views (1-100); rejected when out of range.")
        @Nullable Integer pageSize,
        @Schema(description = "The FULL set of per-section page-size overrides ('dashboard', 'actions', 'notes', 'stats', 'users'): a section left "
        + "out of the array falls back to 'pageSize', an empty array clears every override, unknown section keys are ignored, and a size outside "
        + "1-100 is rejected.")
        @Nullable List<PageSizePref> pageSizes,
        @Schema(examples = "1", description = "Number of decimal places used to render fractional stats (0-2); rejected when out of range.")
        @Nullable Integer decimalPlaces,
        @Schema(examples = "true", description = "Whether the dashboard renders the per-action stats-summary strip.")
        @Nullable Boolean showStatsSummary,
        @Schema(description = "The full ordered 'Action stats' arrangement (key + enabled + optional custom name per stat); unknown keys are "
        + "ignored, a blank or absent name keeps the built-in one (as does a stat's own built-in name), and a name over 25 characters is "
        + "rejected.")
        @Nullable List<StatFieldPref> statsFields) {
    }

    /**
     * The body for changing the current user's password.
     *
     * @param currentPassword the existing password, as proof of ownership
     * @param newPassword     the new password
     */
    // Public is forced: Quarkus's generated (de)serializer is not a nestmate so private throws IllegalAccessError, and the endpoint
    // taking it must be public for JAX-RS, so package-private would trip ClassEscapesItsScope instead.
    @Schema(description = "The current password (as proof of ownership) and the new password to set.")
    @SuppressWarnings({"unused", "WeakerAccess"}) // JSON request body: the canonical constructor is invoked reflectively by Jackson, never from Java
    public record ChangePasswordRequest(
        @Schema(examples = "correct horse battery staple", description = "The existing password, as proof of account ownership.")
        @Nullable String currentPassword,
        @Schema(examples = "correct horse battery staple 2", description = "The new password; at most 128 characters.")
        @Nullable String newPassword) {
    }
}
