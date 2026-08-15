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

package net.zodac.diurnal.auth.session;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.NewCookie;
import org.jspecify.annotations.Nullable;

/**
 * The one place the browser's session cookies are built. A login (password or OIDC) mints the {@code diurnal_session} cookie here, and every path
 * that ends a session — logout, "log out from everywhere" — clears it here, so the cookie's flags cannot drift between the surfaces that set it.
 *
 * <p>
 * The {@code q_session} cookie is Quarkus OIDC's own; Diurnal never sets it, but it does clear it alongside its own cookie so a logout ends both
 * sessions. It is cleared from three places (logout, revoke-all, and the login page dropping a stale one after a refused OIDC login), which is why
 * the builder lives here rather than being written out at each.
 */
@ApplicationScoped
public class SessionCookies {

    /**
     * Quarkus OIDC's session cookie. Set by the framework's code-flow mechanism, never by Diurnal - only cleared.
     */
    public static final String OIDC_COOKIE = "q_session";

    private final SessionConfig sessionConfig;

    /**
     * Injects the session settings the cookie's name and lifetime come from.
     *
     * @param sessionConfig the session settings
     */
    @Inject
    public SessionCookies(final SessionConfig sessionConfig) {
        this.sessionConfig = sessionConfig;
    }

    /**
     * Builds the session cookie carrying a freshly minted token. {@code Secure} is set only for a request that arrived over TLS, so a plain-HTTP
     * local deployment still receives a usable cookie.
     *
     * @param token the raw session token
     * @param routingContext the current request, when available
     * @return the cookie to set on the response
     */
    public NewCookie issued(final String token, final @Nullable RoutingContext routingContext) {
        return new NewCookie.Builder(sessionConfig.cookieName())
                .value(token)
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .secure(isSecureRequest(routingContext))
                .maxAge((int) sessionConfig.absoluteTimeout().toSeconds())
                .build();
    }

    /**
     * Builds the cookie that clears the session cookie.
     *
     * @return the expiring cookie to set on the response
     */
    public NewCookie cleared() {
        return new NewCookie.Builder(sessionConfig.cookieName()).value("").path("/").maxAge(0).httpOnly(true).build();
    }

    /**
     * Builds the cookie that clears Quarkus OIDC's own session cookie.
     *
     * @return the expiring cookie to set on the response
     */
    public static NewCookie clearedOidc() {
        return new NewCookie.Builder(OIDC_COOKIE).value("").path("/").maxAge(0).httpOnly(true).build();
    }

    /**
     * Reads the request's {@code User-Agent}, which every session row records so a user can tell their devices apart.
     *
     * @param routingContext the current request, when available
     * @return the user agent, or {@code null} when there is no request context or no header
     */
    @Nullable
    public static String userAgent(final @Nullable RoutingContext routingContext) {
        return routingContext == null ? null : routingContext.request().getHeader("User-Agent");
    }

    private static boolean isSecureRequest(final @Nullable RoutingContext routingContext) {
        return routingContext != null && routingContext.request().isSSL();
    }
}
