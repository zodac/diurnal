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
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.config.RegistrationConfig;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * REST API authentication endpoints: register a new password user and exchange credentials for a JWT.
 */
@Tag(name = "Auth", description = "Create an account and exchange credentials for a Bearer JWT.")
@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger LOGGER = LogManager.getLogger(AuthResource.class);

    @Inject
    TokenService tokenService;

    @Inject
    RoleAssigner roleAssigner;

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @Inject
    RegistrationConfig registrationConfig;

    @Inject
    LoginThrottles loginThrottles;

    @Inject
    AppClock clock;

    @Context
    @Nullable
    RoutingContext routingContext;

    /**
     * Registers a new password-based user, returning {@code 201} with a JWT, or {@code 409} if the email exists.
     * Returns {@code 404} when password auth is disabled and {@code 403} when registration is disabled, mirroring
     * the web registration guard so the API can never bypass {@code PASSWORD_AUTH_ENABLED}/{@code ENABLE_REGISTRATION}.
     */
    @POST
    @Path("/register")
    @Transactional
    @Operation(
            hidden = true,
            summary = "Register a new user",
            description = "Creates an account and returns a Bearer JWT for it. The first account ever created becomes an administrator."
    )
    public Response register(@Valid final RegisterRequest request) {
        if (!passwordAuthConfig.enabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (!registrationAllowed()) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("Registration is disabled"))
                    .build();
        }

        final String email = request.email().toLowerCase(Locale.ROOT).strip();
        if (User.findByEmail(email).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("Email already registered"))
                    .build();
        }

        final User user = new User();
        user.email = email;
        user.displayName = request.displayName().strip();
        user.passwordHash = Passwords.hash(request.password());
        user.role = roleAssigner.roleForNewUser();
        user.persist();

        LOGGER.info("New user registered: {} (role={})", email, user.role);
        final String token = tokenService.generateToken(user);
        return Response.status(Response.Status.CREATED)
                .entity(new TokenResponse(token, user.email, user.displayName))
                .build();
    }

    /**
     * Validates credentials, returning {@code 200} with a JWT on success or {@code 401} otherwise.
     * Returns {@code 429} (with a {@code Retry-After} header) when the account is temporarily locked
     * out after too many consecutive failures — the response is otherwise indistinguishable from a
     * {@code 401} so a locked account is never disclosed.
     */
    @POST
    @Path("/login")
    @Operation(
            summary = "Log in and obtain a token",
            description = "Validates an email and password and returns a Bearer JWT to send as the Authorization header on subsequent API calls."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Credentials accepted; returns a signed Bearer JWT and basic profile.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = TokenResponse.class))),
        @APIResponse(responseCode = "400",
                description = "The request body is missing the email/password or the email is malformed."),
        @APIResponse(responseCode = "401", description = "Invalid email or password.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class))),
        @APIResponse(responseCode = "429",
                description = "Too many failed attempts for this account; retry after the period in the Retry-After header.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ErrorResponse.class)))
    })
    public Response login(@Valid final LoginRequest request) {
        final String email = request.email().toLowerCase(Locale.ROOT).strip();
        final Instant now = clock.now();

        final String clientIp = ClientAddress.of(routingContext);

        if (loginThrottles.isLocked(email, clientIp, now)) {
            LOGGER.debug("Throttled login attempt for: {} (IP: {})", email, clientIp);
            return lockedResponse(email, clientIp, now);
        }

        final Optional<User> authenticated = User.findByEmail(email)
                .filter(u -> u.passwordHash != null)
                .filter(u -> Passwords.matches(request.password(), u.passwordHash));

        if (authenticated.isPresent()) {
            final User user = authenticated.get();
            loginThrottles.recordSuccess(email);
            LOGGER.debug("Successful login: {}", email);
            return Response.ok(new TokenResponse(tokenService.generateToken(user), user.email, user.displayName)).build();
        }

        final LoginThrottles.ThrottleOutcome outcome = loginThrottles.recordFailure(email, clientIp, now);
        LoginAttemptLog.logFailure(LOGGER, outcome, email, clientIp);
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("Invalid email or password"))
                .build();
    }

    private Response lockedResponse(final String email, final String clientIp, final Instant now) {
        final Duration remaining = loginThrottles.lockoutRemaining(email, clientIp, now);
        return Response.status(Response.Status.TOO_MANY_REQUESTS)
                .header("Retry-After", Math.max(1L, remaining.toSeconds()))
                .entity(new ErrorResponse(LockoutMessages.retryMessage(remaining)))
                .build();
    }

    private boolean registrationAllowed() {
        return User.count() == 0 || registrationConfig.enabled();
    }

    /**
     * Error payload returned for failed auth requests.
     */
    @Schema(description = "Error payload returned when an authentication request is rejected.")
    public record ErrorResponse(
            @Schema(examples = "Invalid email or password", description = "Human-readable description of the error.") String message) {
    }
}
