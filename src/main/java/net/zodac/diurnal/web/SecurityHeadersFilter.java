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
import java.util.Arrays;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Adds a {@code Content-Security-Policy} header to every HTTP response.
 *
 * <p>The {@code frame-ancestors} directive controls which origins may embed this application in a
 * frame. {@code 'self'} is always included; additional origins are read from
 * {@code csrf.trusted.origins} (env: {@code CSRF_TRUSTED_ORIGINS}), comma-separated.
 *
 * <p>Set {@code CSRF_TRUSTED_ORIGINS} when the app is accessed through a reverse proxy so that
 * the Swagger UI iframe loads correctly, e.g.
 * {@code CSRF_TRUSTED_ORIGINS=https://diurnal.example.com,http://127.0.0.1:8080}.
 */
@ApplicationScoped
public class SecurityHeadersFilter {

    @Inject
    Router router;

    @ConfigProperty(name = "csrf.trusted.origins", defaultValue = "")
    String csrfTrustedOrigins = "";

    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes final StartupEvent ev) {
        final String csp = buildFrameAncestorsCsp(csrfTrustedOrigins);
        router.route().order(Integer.MIN_VALUE).handler(ctx -> {
            ctx.response().putHeader("Content-Security-Policy", csp);
            ctx.next();
        });
    }

    /**
     * Builds the {@code frame-ancestors} CSP directive value from a comma-separated origin list.
     *
     * <p>{@code 'self'} is always the first source. Each entry in {@code csrfTrustedOrigins} is
     * stripped of surrounding whitespace; blank entries (e.g. from a trailing comma) are dropped.
     * If no origins remain after filtering, only {@code 'self'} is emitted.
     *
     * @param csrfTrustedOrigins comma-separated origins to permit as frame ancestors; may be blank
     * @return the full {@code frame-ancestors} directive,
     *         e.g. {@code frame-ancestors 'self' https://example.com}
     */
    static String buildFrameAncestorsCsp(final String csrfTrustedOrigins) {
        if (csrfTrustedOrigins.isBlank()) {
            return "frame-ancestors 'self'";
        }
        final String origins = Arrays.stream(csrfTrustedOrigins.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.joining(" "));
        return origins.isEmpty() ? "frame-ancestors 'self'" : "frame-ancestors 'self' " + origins;
    }
}
