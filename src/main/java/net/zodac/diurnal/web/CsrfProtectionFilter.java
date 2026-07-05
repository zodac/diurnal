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

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Rejects cross-site state-changing requests that ride on the session cookie (CSRF defence).
 *
 * <p>The web UI is authenticated by the {@code diurnal_session} (form) or {@code q_session} (OIDC)
 * cookie, which the browser attaches automatically to <em>any</em> request to this origin — including
 * one triggered by an attacker's page. This filter closes that gap by validating, on every unsafe
 * HTTP method (POST/PUT/PATCH/DELETE), that the request's {@code Origin} (or, absent that,
 * {@code Referer}) matches the host the browser actually addressed
 * ({@code X-Forwarded-Host}, falling back to {@code Host}). An attacker's page cannot forge either
 * header, so a cross-site forgery is detected and rejected with {@code 403}.
 *
 * <p>Scope decisions, and why they are safe:
 * <ul>
 *   <li><strong>Only cookie-authenticated requests are guarded.</strong> A Bearer/Basic API call
 *       (no session cookie) is not a CSRF vector — the credential is not ambient — so it is left
 *       alone.</li>
 *   <li><strong>Requests with neither {@code Origin} nor {@code Referer} are allowed.</strong>
 *       Browsers always attach an {@code Origin} to a cross-site POST/PUT/PATCH/DELETE, so their
 *       total absence means a non-browser client (curl, a test harness) that is not driving a
 *       victim's ambient cookie — again, not a CSRF vector.</li>
 *   <li><strong>A present-but-mismatched (or opaque {@code "null"}) {@code Origin} is rejected.</strong>
 *       This closes the sandboxed-iframe {@code Origin: null} bypass.</li>
 * </ul>
 *
 * <p>This complements — and is defence-in-depth over — the {@code SameSite=Strict} attribute set on
 * the session cookie (see {@code application.properties}).
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class CsrfProtectionFilter implements ContainerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final String SESSION_COOKIE = "diurnal_session";
    private static final String OIDC_COOKIE = "q_session";
    private static final String FORWARDED_HOST_HEADER = "X-Forwarded-Host";
    private static final String HOST_HEADER = "Host";
    private static final String ORIGIN_HEADER = "Origin";
    private static final String REFERER_HEADER = "Referer";

    @Override
    public void filter(final ContainerRequestContext requestContext) {
        final boolean cookieAuthenticated = requestContext.getCookies().containsKey(SESSION_COOKIE)
            || requestContext.getCookies().containsKey(OIDC_COOKIE);
        final String expectedAuthority = expectedAuthority(
            requestContext.getHeaderString(FORWARDED_HOST_HEADER),
            requestContext.getHeaderString(HOST_HEADER));

        if (isCsrfViolation(
            requestContext.getMethod(),
            cookieAuthenticated,
            requestContext.getHeaderString(ORIGIN_HEADER),
            requestContext.getHeaderString(REFERER_HEADER),
            expectedAuthority)) {
            requestContext.abortWith(Response
                .status(Response.Status.FORBIDDEN)
                .entity("CSRF validation failed: request origin does not match this site")
                .type(MediaType.TEXT_PLAIN_TYPE)
                .build());
        }
    }

    /**
     * Decides whether a request is a cross-site request forgery that must be rejected.
     *
     * @param method              the HTTP method
     * @param cookieAuthenticated whether the request carries a session (form/OIDC) cookie
     * @param origin              the {@code Origin} header value, or {@code null} if absent
     * @param referer             the {@code Referer} header value, or {@code null} if absent
     * @param expectedAuthority   the {@code host[:port]} the browser addressed, or {@code null}
     * @return {@code true} if the request must be rejected as a CSRF attempt
     */
    static boolean isCsrfViolation(final String method,
                                   final boolean cookieAuthenticated,
                                   final @Nullable String origin,
                                   final @Nullable String referer,
                                   final @Nullable String expectedAuthority) {
        if (SAFE_METHODS.contains(method) || !cookieAuthenticated) {
            return false;
        }

        // A present Origin is authoritative: it must parse to and match the addressed host. An opaque
        // origin (the literal "null") or any mismatch is a violation.
        if (origin != null) {
            return !authorityMatches(origin, expectedAuthority);
        }
        // No Origin, but a Referer can stand in as the initiating page's origin. When neither header
        // is present it is a non-browser client (not a CSRF vector), so the request is allowed.
        return referer != null && !authorityMatches(referer, expectedAuthority);
    }

    private static boolean authorityMatches(final String url, final @Nullable String expectedAuthority) {
        if (expectedAuthority == null || expectedAuthority.isBlank()) {
            return false;
        }
        final String sourceAuthority = authorityOf(url);
        return sourceAuthority != null && sourceAuthority.equalsIgnoreCase(expectedAuthority);
    }

    /**
     * Extracts the {@code host[:port]} authority from an absolute URL (an {@code Origin} has no path;
     * a {@code Referer} does).
     *
     * @param url the absolute URL to parse
     * @return the {@code host[:port]} authority, or {@code null} for a relative URL, the opaque
     *         {@code "null"} origin, or an empty authority
     */
    static @Nullable String authorityOf(final String url) {
        final int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return null;
        }
        final String afterScheme = url.substring(schemeEnd + 3);
        final int pathStart = afterScheme.indexOf('/');
        final String authority = pathStart < 0 ? afterScheme : afterScheme.substring(0, pathStart);
        return authority.isBlank() ? null : authority;
    }

    /**
     * Resolves the {@code host[:port]} the browser addressed, to compare against a request's origin.
     *
     * <p>Behind a reverse proxy the browser-facing host arrives as {@code X-Forwarded-Host}; a
     * multi-proxy chain sends a comma-separated list, of which the first entry is the original
     * client-facing host. Falls back to the {@code Host} header when not proxied.
     *
     * @param forwardedHost the {@code X-Forwarded-Host} header value, or {@code null} if absent
     * @param host          the {@code Host} header value, or {@code null} if absent
     * @return the client-facing {@code host[:port]} authority, or {@code null} if neither is present
     */
    static @Nullable String expectedAuthority(final @Nullable String forwardedHost, final @Nullable String host) {
        final String source = forwardedHost != null && !forwardedHost.isBlank() ? forwardedHost : host;
        if (source == null || source.isBlank()) {
            return null;
        }
        final int comma = source.indexOf(',');
        final String first = comma < 0 ? source : source.substring(0, comma);
        final String trimmed = first.strip();
        return trimmed.isBlank() ? null : trimmed;
    }
}
