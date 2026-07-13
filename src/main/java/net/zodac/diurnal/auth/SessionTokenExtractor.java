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

import io.vertx.core.http.Cookie;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * Single source of truth for reading the raw opaque session token off an HTTP request: the {@code diurnal_session} cookie takes precedence (the
 * browser case), falling back to an {@code Authorization: Bearer} header (the API case).
 *
 * <p>
 * Shared by {@link SessionAuthMechanism} (which authenticates every route) and {@link OpenApiDocsAuthFilter} (which gates the documentation surface)
 * so the two never drift on how a token is located.
 */
final class SessionTokenExtractor {

    private static final String BEARER_PREFIX = "Bearer ";

    private SessionTokenExtractor() {

    }

    /**
     * Extracts the raw session token from the request, preferring the session cookie over a Bearer header. Returns {@code null} when neither carries
     * a non-blank value.
     *
     * @param context the request's routing context
     * @param cookieName the configured session cookie name
     * @return the raw token, or {@code null} if absent
     */
    static @Nullable String fromRequest(final RoutingContext context, final String cookieName) {
        final Cookie cookie = context.request().getCookie(cookieName);
        if (cookie != null && !cookie.getValue().isBlank()) {
            return cookie.getValue();
        }

        final String authorization = context.request().getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            final String token = authorization.substring(BEARER_PREFIX.length()).strip();
            return token.isBlank() ? null : token;
        }
        return null;
    }
}
