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

package net.zodac.diurnal.auth.security;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Set;
import net.zodac.diurnal.config.AppConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Rejects cross-site state-changing requests that ride on the session cookie (CSRF defence).
 *
 * <p>
 * The web UI is authenticated by the {@code diurnal_session} (form) or {@code q_session} (OIDC) cookie, which the browser attaches automatically to
 * <em>any</em> request to this origin — including one triggered by an attacker's page. This filter closes that gap by validating, on every unsafe
 * HTTP method (POST/PUT/PATCH/DELETE), that the request's {@code Origin} (or, absent that, {@code Referer}) matches the host the browser actually
 * addressed ({@code X-Forwarded-Host} when forwarded headers are trusted, otherwise {@code Host}). An attacker's page cannot forge either header, so
 * a cross-site forgery is detected and rejected with {@code 403}.
 *
 * <p>
 * Scope decisions, and why they are safe:
 * <ul>
 *   <li><strong>Only cookie-authenticated requests are guarded.</strong> A Bearer-token API call
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
 * <p>
 * This complements — and is defence-in-depth over — the {@code SameSite=Strict} attribute set on the session cookie (see
 * {@code application.properties}).
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class CsrfProtectionFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = LogManager.getLogger(CsrfProtectionFilter.class);

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final String SESSION_COOKIE = "diurnal_session";
    private static final String OIDC_COOKIE = "q_session";
    private static final String FORWARDED_HOST_HEADER = "X-Forwarded-Host";
    private static final String HOST_HEADER = "Host";
    private static final String ORIGIN_HEADER = "Origin";
    private static final String REFERER_HEADER = "Referer";

    private final Instance<AppConfig> appConfig;

    /**
     * Injects the application configuration carrying the proxy-trust flag that decides whether {@code X-Forwarded-Host} may be believed.
     *
     * <p>
     * Taken as a lazy {@link Instance} rather than the bean itself, and this is load-bearing: a JAX-RS {@code @Provider} is instantiated while the
     * REST layer builds its interceptor deployment, which happens BEFORE the {@code @ConfigMapping} beans are registered with the runtime config.
     * Injecting {@link AppConfig} directly therefore fails the packaged application at boot with {@code SRCFG00027: Could not find a mapping} - and
     * fails it ONLY there, since {@code @QuarkusTest} has its config in place before the deployment is built, so every unit test, IT and E2E run
     * stays green. Resolving the bean at request time instead sidesteps the ordering entirely. Do not "simplify" this to a direct injection.
     *
     * @param appConfig the deferred handle to the typed view over {@code app.*}
     */
    @Inject
    public CsrfProtectionFilter(final Instance<AppConfig> appConfig) {
        this.appConfig = appConfig;
    }

    @Override
    public void filter(final ContainerRequestContext requestContext) {
        final boolean cookieAuthenticated = requestContext.getCookies().containsKey(SESSION_COOKIE)
            || requestContext.getCookies().containsKey(OIDC_COOKIE);
        final String expectedAuthority = expectedAuthority(
            requestContext.getHeaderString(FORWARDED_HOST_HEADER),
            requestContext.getHeaderString(HOST_HEADER),
            appConfig.get().trustForwardedHeaders());

        if (isCsrfViolation(
            requestContext.getMethod(),
            cookieAuthenticated,
            requestContext.getHeaderString(ORIGIN_HEADER),
            requestContext.getHeaderString(REFERER_HEADER),
            expectedAuthority)) {
            // A genuine CSRF attempt or a misconfigured reverse proxy sending the wrong X-Forwarded-Host both trip this - log the
            // request origin against the addressed host so either can be told apart. Kept to a single line as it is security-relevant.
            LOGGER.warn("Rejected cross-site {} /{} - origin '{}' (referer '{}') does not match this site '{}'",
                requestContext.getMethod(),
                requestContext.getUriInfo().getPath(),
                requestContext.getHeaderString(ORIGIN_HEADER),
                requestContext.getHeaderString(REFERER_HEADER),
                expectedAuthority);
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
            return authorityDoesNotMatch(origin, expectedAuthority);
        }
        // No Origin, but a Referer can stand in as the initiating page's origin. When neither header
        // is present it is a non-browser client (not a CSRF vector), so the request is allowed.
        return referer != null && authorityDoesNotMatch(referer, expectedAuthority);
    }

    private static boolean authorityDoesNotMatch(final String url, final @Nullable String expectedAuthority) {
        if (expectedAuthority == null || expectedAuthority.isBlank()) {
            return true;
        }

        final String sourceAuthority = authorityOf(url);
        return !expectedAuthority.equalsIgnoreCase(sourceAuthority);
    }

    /**
     * Extracts the {@code host[:port]} authority from an absolute URL (an {@code Origin} has no path; a {@code Referer} does).
     *
     * @param url the absolute URL to parse
     * @return the {@code host[:port]} authority, or {@code null} for a relative URL, the opaque {@code "null"} origin, or an empty authority
     */
    @Nullable
    static String authorityOf(final String url) {
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
     * <p>
     * Behind a reverse proxy the browser-facing host arrives as {@code X-Forwarded-Host}; a multi-proxy chain sends a comma-separated list, of which
     * the first entry is the original client-facing host. Falls back to the {@code Host} header when not proxied.
     *
     * <p>
     * {@code X-Forwarded-Host} is only consulted when the deployment declares its proxy trusted ({@code TRUST_X_FORWARDED_HEADERS}, the same flag
     * that lets the HTTP layer derive the request scheme/host from the forwarded headers). When the app is exposed directly, any client can send
     * that header, so believing it here would let a request nominate the authority it is then checked against - the check would compare the
     * attacker's value with itself. Trusting it only where the rest of the app already does keeps this filter on the one configured trust boundary.
     *
     * @param forwardedHost         the {@code X-Forwarded-Host} header value, or {@code null} if absent
     * @param host                  the {@code Host} header value, or {@code null} if absent
     * @param trustForwardedHeaders whether the deployment sits behind a trusted reverse proxy
     * @return the client-facing {@code host[:port]} authority, or {@code null} if neither is present
     */
    @Nullable
    static String expectedAuthority(final @Nullable String forwardedHost,
        final @Nullable String host,
        final boolean trustForwardedHeaders) {
        final boolean useForwardedHost = trustForwardedHeaders && forwardedHost != null && !forwardedHost.isBlank();
        final String source = useForwardedHost ? forwardedHost : host;
        if (source == null || source.isBlank()) {
            return null;
        }
        final int comma = source.indexOf(',');
        final String first = comma < 0 ? source : source.substring(0, comma);
        final String trimmed = first.strip();
        return trimmed.isBlank() ? null : trimmed;
    }
}
