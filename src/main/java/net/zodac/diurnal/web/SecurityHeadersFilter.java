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

package net.zodac.diurnal.web;

import io.quarkus.runtime.StartupEvent;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Adds a {@code Content-Security-Policy} header to every HTTP response.
 *
 * <p>The {@code frame-ancestors 'self'} directive prevents any other origin from embedding this
 * application in a frame (anti-clickjacking), while still allowing the app to frame its own
 * same-origin pages — notably the in-app Swagger UI iframe ({@code <iframe src="/api">}), which is
 * always same-origin and so covered by {@code 'self'} in every deployment (dev, direct, or behind a
 * reverse proxy). No configuration is required.
 *
 * <p>This is <strong>not</strong> a CSRF control — it does not stop a cross-site request from being
 * sent, only which origins may frame the page. Request-origin validation for state-changing requests
 * is handled separately by {@link CsrfProtectionFilter}.
 */
@ApplicationScoped
public class SecurityHeadersFilter {

    private static final String CONTENT_SECURITY_POLICY = "frame-ancestors 'self'";

    @Inject
    Router router;

    /**
     * Registers a top-priority Vert.x route that adds the {@code Content-Security-Policy} header to
     * every HTTP response.
     *
     * @param ev the application startup event that triggers route registration
     */
    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes final StartupEvent ev) {
        router.route().order(Integer.MIN_VALUE).handler(ctx -> {
            ctx.response().putHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
            ctx.next();
        });
    }
}
