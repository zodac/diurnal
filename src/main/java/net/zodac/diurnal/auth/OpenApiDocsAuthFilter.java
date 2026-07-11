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

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.HttpURLConnection;
import java.util.Optional;
import net.zodac.diurnal.config.SessionConfig;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;

/**
 * Gates the OpenAPI documentation surface behind the {@code admin} role. Both the Swagger UI shell
 * ({@code /api}) and the generated OpenAPI document ({@code /q/openapi}) are served in every profile
 * and sit on {@code permit} paths, so without this filter they are reachable anonymously.
 *
 * <p>
 * Because {@code quarkus.http.auth.proactive=false} leaves those framework-served paths with no
 * resolved {@link io.quarkus.security.identity.SecurityIdentity} (a named roles-allowed HTTP policy
 * would not fire), this is enforced as a low-order Vert.x route that runs before the framework
 * handlers: it resolves the request's session token itself via the {@link SessionStore} — mirroring
 * {@link SessionAuthMechanism} — and applies the {@link OpenApiDocsAccess} decision, only calling
 * {@code next()} for an administrator.
 */
@ApplicationScoped
public class OpenApiDocsAuthFilter {

    // Runs immediately after SecurityHeadersFilter (Integer.MIN_VALUE) and well before every framework
    // route, so the guard decides before the Swagger UI / OpenAPI handlers ever see the request.
    private static final int GUARD_ROUTE_ORDER = Integer.MIN_VALUE + 1;

    @Inject
    Router router;

    @Inject
    SessionStore sessionStore;

    @Inject
    SessionConfig sessionConfig;

    @Inject
    AppClock clock;

    /**
     * Registers the admin guard on the documentation routes at application startup.
     *
     * @param ev the startup event that triggers route registration
     */
    @SuppressWarnings("unused")
    // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes final StartupEvent ev) {
        router.routeWithRegex(OpenApiDocsPaths.SWAGGER_UI_PATH_REGEX).order(GUARD_ROUTE_ORDER).blockingHandler(this::guard);
        router.routeWithRegex(OpenApiDocsPaths.OPENAPI_DOCUMENT_PATH_REGEX).order(GUARD_ROUTE_ORDER).blockingHandler(this::guard);
    }

    private void guard(final RoutingContext context) {
        final Optional<User> resolvedUser = resolveUser(context);
        final OpenApiDocsAccess.Outcome outcome = OpenApiDocsAccess.decide(resolvedUser);
        switch (outcome) {
            case ALLOW -> context.next();
            case FORBIDDEN -> context.response().setStatusCode(HttpURLConnection.HTTP_FORBIDDEN).end();
            default -> context.response()
                .setStatusCode(HttpURLConnection.HTTP_MOVED_TEMP)
                .putHeader("location", "/login")
                .end();
        }
    }

    private Optional<User> resolveUser(final RoutingContext context) {
        final String token = SessionTokenExtractor.fromRequest(context, sessionConfig.cookieName());
        if (token == null) {
            return Optional.empty();
        }
        return sessionStore.resolve(token, clock.now());
    }
}
