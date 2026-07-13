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
 * Adds security-related HTTP headers to every response: a {@code Content-Security-Policy} — strict on every route except the admin-gated OpenAPI
 * documentation surface, which gets a relaxed variant (see {@link CspPolicy}) — plus a set of static, content-independent hardening headers.
 *
 * <p>
 * The strict policy's {@code frame-ancestors 'self'} directive prevents any other origin from embedding this application in a frame
 * (anti-clickjacking), while still allowing the app to frame its own same-origin pages — notably the in-app Swagger UI iframe
 * ({@code <iframe src="/api">}), which is always same-origin and so covered by {@code 'self'} in every deployment (dev, direct, or behind a reverse
 * proxy). {@code base-uri 'self'} and {@code form-action 'self'} stop a base-tag or form-target injection from redirecting the page/submissions
 * off-origin; {@code object-src 'none'} blocks legacy {@code <object>}/{@code <embed>} plugin content, which this app never serves.
 * {@code script-src 'self' 'sha256-…'} allows only same-origin scripts plus the one pinned inline block (the FOUC theme bootstrap in
 * {@code layout.html}), and {@code script-src-attr 'none'} blocks every inline {@code on*=} event-handler attribute — the app has none left.
 * {@code style-src 'self' 'sha256-…'} mirrors the same pattern for the one inline {@code <style>} that bootstrap conditionally injects (the dark-mode
 * FOUC background override); {@code style-src-attr 'unsafe-inline'} is the one deliberate laxity left in the policy — the app renders per-user swatch
 * colours as inline {@code style="…"} attributes, which can't be a static class or a pinned hash, and that directive can't execute script.
 * {@code default-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'} close the remaining fetch-destination directives — an audit found
 * no {@code data:} URI, cross-origin font, or cross-origin fetch/HTMX target anywhere in the app, so all four stay at {@code 'self'} with no
 * relaxation. No configuration is required.
 *
 * <p>
 * {@code X-Frame-Options: SAMEORIGIN} is a legacy backstop for {@code frame-ancestors 'self'} in browsers that predate CSP frame-ancestors support —
 * {@code SAMEORIGIN}, not {@code DENY}, for the same same-origin Swagger UI iframe reason above. {@code X-Content-Type-Options: nosniff} stops the
 * browser MIME-sniffing a response away from its declared {@code Content-Type}. {@code Referrer-Policy: strict-origin-when-cross-origin} avoids
 * leaking full URLs (which may carry path-based identifiers) to other origins while still sending a useful referrer same-origin.
 * {@code Cross-Origin-Opener-Policy: same-origin} isolates this app's browsing context group from cross-origin popups/openers.
 * {@code Permissions-Policy} denies browser APIs the app never uses. {@code Cross-Origin-Resource-Policy: same-origin} is safe here because every
 * asset (fonts, favicons, the PWA manifest icons) is served same-origin — CORP only governs cross-origin {@code no-cors} fetches, which this app
 * never receives.
 *
 * <p>
 * This is <strong>not</strong> a CSRF control — it does not stop a cross-site request from being sent, only which origins may frame the page.
 * Request-origin validation for state-changing requests is handled separately by {@link CsrfProtectionFilter}.
 */
@ApplicationScoped
public class SecurityHeadersFilter {

    private static final String PERMISSIONS_POLICY = "geolocation=(), camera=(), microphone=(), payment=()";

    @Inject
    Router router;

    /**
     * Registers a top-priority Vert.x route that adds security headers to every HTTP response.
     *
     * @param ev the application startup event that triggers route registration
     */
    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes final StartupEvent ev) {
        router.route().order(Integer.MIN_VALUE).handler(ctx -> {
            final var headers = ctx.response().headers();
            headers.add("Content-Security-Policy", CspPolicy.forPath(ctx.request().path()));
            headers.add("X-Content-Type-Options", "nosniff");
            headers.add("Referrer-Policy", "strict-origin-when-cross-origin");
            headers.add("X-Frame-Options", "SAMEORIGIN");
            headers.add("Cross-Origin-Opener-Policy", "same-origin");
            headers.add("Permissions-Policy", PERMISSIONS_POLICY);
            headers.add("Cross-Origin-Resource-Policy", "same-origin");
            ctx.next();
        });
    }
}
