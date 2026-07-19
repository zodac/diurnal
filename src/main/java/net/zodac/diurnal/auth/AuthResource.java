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

package net.zodac.diurnal.auth;

import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.config.RegistrationConfig;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * REST API authentication endpoints: register a new password user, exchange credentials for an opaque session token, and revoke the current
 * token ({@code /logout}) or every session for the account ({@code /revoke}).
 */
@Tag(name = "Auth", description = "Create an account and exchange credentials for a Bearer session token.")
@Path("/api/v1/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger LOGGER = LogManager.getLogger(AuthResource.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SETUP_REQUIRED_MESSAGE = "The initial administrator account must be created via the setup page";

    @Inject
    AuthenticationService authenticationService;

    @Inject
    CurrentUser currentUser;

    @Inject
    RegistrationService registrationService;

    @Inject
    SessionStore sessionStore;

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @Inject
    RegistrationConfig registrationConfig;

    @Inject
    AppClock clock;

    @Context
    @Nullable
    RoutingContext routingContext;

    /**
     * Registers a new password-based user, returning {@code 201} with a session token, or {@code 409} if the email exists. Returns {@code 404} when
     * password auth is disabled and {@code 403} when either registration is disabled or the initial account has not yet been created. The very first
     * (administrator) account can never be created through this endpoint — it must be created locally via the web setup flow ({@code /welcome} →
     * {@code POST /register}), so an unauthenticated caller can never seize the initial admin account. Validation and account creation are the shared
     * {@link RegistrationService} the web form also calls, so the rules cannot diverge; the {@code PASSWORD_AUTH_ENABLED}/{@code ENABLE_REGISTRATION}
     * guards are enforced here too, so the API can never bypass them.
     */
    @POST
    @Path("/register")
    @Transactional
    @Operation(
        summary = "Register a new user",
        description = "Creates an account and returns a Bearer session token for it. The initial administrator account cannot be created here — it "
        + "must be created locally through the setup page first."
    )
    @APIResponses({
        @APIResponse(responseCode = "201", description = "The account was created; returns a Bearer session token and basic profile.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = TokenResponse.class))),
        @APIResponse(responseCode = "400", description = "The email, display name or password is missing or invalid."),
        @APIResponse(responseCode = "403",
                description = "Registration is disabled, or the initial administrator account has not been created via the setup page yet.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(responseCode = "404", description = "Password-based authentication is disabled on this deployment."),
        @APIResponse(responseCode = "409", description = "The email is already registered.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(responseCode = "429",
                description = "Too many failed attempts; retry after the period in the Retry-After header.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Response register(final @Nullable RegisterRequest request) {
        // Surface policy (deliberately different from the web form): the API can never create the very
        // first (administrator) account — that must be done locally through the web setup flow
        // (/welcome → POST /register), so an unauthenticated caller cannot claim it. The shared
        // validation/creation rules live in RegistrationService.
        if (!passwordAuthConfig.enabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (User.count() == 0) {
            LOGGER.warn("Refusing API registration before the initial account exists - it must be created via the setup page");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse(SETUP_REQUIRED_MESSAGE))
                    .build();
        }
        if (!registrationConfig.enabled()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("Registration is disabled"))
                    .build();
        }

        // No confirmPassword: an API client confirms the password on its own side.
        final RegistrationResult result = registrationService.register(
            request == null ? null : request.email(),
            request == null ? null : request.displayName(),
            request == null ? null : request.password(),
            null, ClientAddress.of(routingContext), clock.now());

        return switch (result) {
            case RegistrationResult.Success success -> {
                final User user = success.user();
                yield Response.status(Response.Status.CREATED)
                        .entity(new TokenResponse(newSession(user), user.email, user.displayName))
                        .build();
            }
            case RegistrationResult.LockedOut locked -> lockedResponse(locked.remaining());
            case RegistrationResult.Invalid invalid -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse(invalidMessage(invalid)))
                    .build();
            case RegistrationResult.DuplicateEmail ignored -> Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("Email already registered"))
                    .build();
        };
    }

    private static String invalidMessage(final RegistrationResult.Invalid invalid) {
        final List<String> parts = new ArrayList<>();
        if (!invalid.missingFields().isEmpty()) {
            parts.add("Missing required fields: " + String.join(", ", invalid.missingFields()));
        }
        parts.addAll(invalid.errors());
        return String.join(" ", parts);
    }

    /**
     * Validates credentials, returning {@code 200} with an opaque session token on success or {@code 401} otherwise. Returns {@code 429} (with a
     * {@code Retry-After} header) when the client is temporarily locked out after too many failed attempts — the response is otherwise
     * indistinguishable from a {@code 401} so a locked account is never disclosed.
     */
    @POST
    @Path("/login")
    @Operation(
        summary = "Log in and obtain a token",
        description = "Validates an email and password and returns an opaque Bearer session token for the Authorization header on later calls."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Credentials accepted; returns a Bearer session token and basic profile.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = TokenResponse.class))),
        @APIResponse(responseCode = "400",
                description = "The request body is missing the email/password or the email is malformed."),
        @APIResponse(responseCode = "401", description = "Invalid email or password.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(responseCode = "429",
                description = "Too many failed attempts; retry after the period in the Retry-After header.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Response login(@Valid final LoginRequest request) {
        final String clientIp = ClientAddress.of(routingContext);
        final Instant now = clock.now();
        final LoginResult result = authenticationService.authenticate(request.email(), request.password(), clientIp, now);

        return switch (result) {
            case LoginResult.Success success -> {
                final User user = success.user();
                LOGGER.debug("Successful API login: {}", user.email);
                yield Response.ok(new TokenResponse(newSession(user), user.email, user.displayName)).build();
            }
            case LoginResult.LockedOut locked -> lockedResponse(locked.remaining());
            case LoginResult.InvalidCredentials ignored -> Response.status(Response.Status.UNAUTHORIZED)
                    .entity(new ErrorResponse("Invalid email or password"))
                    .build();
        };
    }

    /**
     * Revokes the session token used to make this request, returning {@code 204}. A missing or malformed Authorization header is a no-op (still
     * {@code 204}); an unauthenticated request is challenged with {@code 401} before it reaches here.
     */
    @POST
    @Path("/logout")
    // Overrides the class-level JSON @Consumes: this endpoint takes no body, so any (or no)
    // Content-Type must be accepted rather than rejected with a 415 (same as /revoke).
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed(Role.Values.USER)
    @SecurityRequirement(name = "BearerAuth")
    @Operation(summary = "Log out", description = "Revokes the Bearer session token used to make this request.")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "The session token was revoked (a missing/malformed header is a no-op)."),
        @APIResponse(responseCode = "401", description = "No valid session token was supplied.")
    })
    public Response logout(@HeaderParam("Authorization") @Nullable final String authorization) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            sessionStore.revoke(authorization.substring(BEARER_PREFIX.length()).strip());
        }
        return Response.noContent().build();
    }

    /**
     * Revokes <strong>every</strong> session for the authenticated account — web logins and API tokens alike, including the token used to make this
     * request — returning {@code 204}. The API twin of the Settings page's "Log out from everywhere": both call the same
     * {@link SessionStore#revokeAllForUser(java.util.UUID)}, so the semantics cannot diverge. Intended as the panic switch after a suspected token
     * leak; the caller must log in again afterwards.
     */
    @POST
    @Path("/revoke")
    // Overrides the class-level JSON @Consumes: this endpoint takes no body, so any (or no)
    // Content-Type must be accepted rather than rejected with a 415.
    @Consumes(MediaType.WILDCARD)
    @RolesAllowed(Role.Values.USER)
    @SecurityRequirement(name = "BearerAuth")
    @Operation(
        summary = "Revoke all sessions (log out from everywhere)",
        description = "Revokes EVERY session for the account — web logins and API tokens alike, including the token used to make this request. "
        + "Equivalent to the Settings page's 'Log out from everywhere'. All clients must re-authenticate afterwards."
    )
    @APIResponses({
        @APIResponse(responseCode = "204", description = "Every session for the account was revoked, including this request's token."),
        @APIResponse(responseCode = "401", description = "No valid session token was supplied.")
    })
    public Response revokeAllSessions() {
        final User user = currentUser.get();
        sessionStore.revokeAllForUser(user.id);
        LOGGER.info("All sessions revoked for user: {} (log out from everywhere)", user.email);
        return Response.noContent().build();
    }

    private String newSession(final User user) {
        final String userAgent = routingContext == null ? null : routingContext.request().getHeader("User-Agent");
        return sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, userAgent, ClientAddress.of(routingContext), clock.now());
    }

    // Shared by both the login and registration lockouts — the message is neutral across surfaces (one
    // shared per-IP counter feeds both), so there is a single 429 response builder.
    private Response lockedResponse(final Duration remaining) {
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .header("Retry-After", Math.max(1L, remaining.toSeconds()))
                .entity(new ErrorResponse(LockoutMessages.retryMessage(remaining)))
                .build();
    }

    /**
     * Error payload returned for failed auth requests.
     */
    @Schema(description = "Error payload returned when an authentication request is rejected.")
    public record ErrorResponse(
        @Schema(examples = "Invalid email or password", description = "Human-readable description of the error.") String message) {
    }
}
