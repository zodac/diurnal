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

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Authenticates form/API password logins by verifying the BCrypt hash and building the identity.
 */
@ApplicationScoped
public class PasswordIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    private static final Logger LOGGER = LogManager.getLogger(PasswordIdentityProvider.class);

    /** Short-lived cookie signalling that the just-rejected form login was a lockout, not a bad password. */
    public static final String LOCKOUT_COOKIE = "diurnal_login_lockout";
    // Only needs to survive the immediate redirect to the login page.
    private static final long LOCKOUT_COOKIE_MAX_AGE_SECONDS = 30L;

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @Inject
    LoginThrottles loginThrottles;

    @Inject
    AppClock clock;

    // Self-injection gives us the CDI proxy, so @Transactional on verifyCredentials is honoured.
    @Inject
    PasswordIdentityProvider self;

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            final UsernamePasswordAuthenticationRequest request,
            final AuthenticationRequestContext context) {
        // Credentials extracted here (still on IO thread, no blocking work).
        final String email = request.getUsername().toLowerCase(Locale.ROOT).strip();
        final char[] raw = request.getPassword().getPassword();
        final String password = new String(raw);
        Arrays.fill(raw, '\0');

        if (!passwordAuthConfig.enabled()) {
            return Uni.createFrom().failure(new AuthenticationFailedException("Password authentication is disabled."));
        }

        final RoutingContext routingContext = HttpSecurityUtils.getRoutingContextAttribute(request);
        final String clientIp = ClientAddress.of(routingContext);

        // Lockout is enforced here on the IO thread (no blocking work) so we can drop a short-lived
        // cookie onto the outgoing redirect — the login page reads it to show the "too many attempts"
        // banner. Quarkus form auth's redirect target is static, so this cookie is how the reason
        // survives to the GET /login render.
        if (loginThrottles.isLocked(email, clientIp, clock.now())) {
            LOGGER.debug("Throttled login attempt for: {} (IP: {})", email, clientIp);
            final long remainingSeconds = Math.max(1L, loginThrottles.lockoutRemaining(email, clientIp, clock.now()).toSeconds());
            markLockedOut(routingContext, remainingSeconds);
            return Uni.createFrom().failure(new AuthenticationFailedException());
        }

        // runBlocking moves execution to a worker thread — JTA and BCrypt are both safe there.
        return context.runBlocking(() -> self.verifyCredentials(email, password, clientIp));
    }

    /**
     * Sets the short-lived lockout cookie (value = seconds left on the lockout) on the current response,
     * if the request's {@link RoutingContext} is available. The login page reads it to show the banner and
     * seed the countdown. Best-effort: if it is not available (e.g. the API path), the user sees the
     * generic message.
     */
    private void markLockedOut(@Nullable final RoutingContext routingContext, final long remainingSeconds) {
        if (routingContext != null) {
            routingContext.response().addCookie(Cookie.cookie(LOCKOUT_COOKIE, Long.toString(remainingSeconds))
                    .setPath("/").setHttpOnly(true).setMaxAge(LOCKOUT_COOKIE_MAX_AGE_SECONDS));
        }
    }

    /**
     * Verifies the password against the stored hash, updating {@code lastLoginAt} on success. The
     * caller has already confirmed the account is not locked out; {@code clientIp} is used only for
     * security logging.
     */
    @Transactional
    SecurityIdentity verifyCredentials(final String email, final String password, final String clientIp) {
        final Optional<User> match = User.findByEmail(email)
                .filter(u -> u.passwordHash != null)
                .filter(u -> Passwords.matches(password, u.passwordHash));

        if (match.isEmpty()) {
            final LoginThrottles.ThrottleOutcome outcome = loginThrottles.recordFailure(email, clientIp, clock.now());
            LoginAttemptLog.logFailure(LOGGER, outcome, email, clientIp);
            throw new AuthenticationFailedException();
        }

        final User user = match.get();
        loginThrottles.recordSuccess(email);
        LOGGER.debug("Password login: name={} email={} role={}", user.displayName, user.email, user.role);
        user.lastLoginAt = Instant.now();
        user.persist();
        final var builder = QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(user.email))
                .addAttribute("userId", user.id.toString())
                .addAttribute("displayName", user.displayName)
                .addRole(User.ROLE_USER);
        if (user.isAdmin()) {
            builder.addRole(User.ROLE_ADMIN);
        }
        return builder.build();
    }
}
