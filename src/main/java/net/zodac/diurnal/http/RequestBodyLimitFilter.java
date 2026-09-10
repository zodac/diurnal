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

package net.zodac.diurnal.http;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.OptionalLong;
import net.zodac.diurnal.config.AppConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Rejects an over-sized request body with {@code 413} before it is read, on every endpoint EXCEPT the data-import endpoints.
 *
 * <p>
 * The HTTP layer's own {@code quarkus.http.limits.max-body-size} has to stay large enough for a re-imported export (tens of MB), but that same
 * ceiling applied to every other endpoint is a cheap memory-exhaustion lever: an unauthenticated caller could make the server buffer a
 * multi-megabyte body on a hot path like {@code POST /api/v1/auth/login} (each of which then also runs an Argon2id hash). This filter caps every
 * body at the far smaller {@code app.http.max-request-body} using the request's {@code Content-Length}, and exempts only the import endpoints
 * ({@code /api/v1/data/import*}, {@code /internal/data/import*}), which legitimately need the larger ceiling.
 *
 * <p>
 * A body with no {@code Content-Length} (a chunked upload) is not measured here and is still bounded by {@code max-body-size}; a client that
 * understates {@code Content-Length} only causes the server to read that many bytes, so the header is a sound basis for the check. The cap is checked
 * on {@code Content-Length} alone, so it costs nothing and runs before authentication.
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
public class RequestBodyLimitFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = LogManager.getLogger(RequestBodyLimitFilter.class);

    private static final String API_IMPORT_PATH_PREFIX = "api/v1/data/import";
    private static final String INTERNAL_IMPORT_PATH_PREFIX = "internal/data/import";

    private final Instance<AppConfig> appConfig;

    /**
     * Injects the application configuration carrying the per-request body cap.
     *
     * <p>
     * Taken as a lazy {@link Instance} rather than the bean itself for the same reason as {@code CsrfProtectionFilter}: a JAX-RS {@code @Provider} is
     * instantiated while the REST layer builds its interceptor deployment, BEFORE the {@code @ConfigMapping} beans are registered with the runtime
     * config, so injecting {@link AppConfig} directly fails the packaged application at boot with {@code SRCFG00027}. Resolving it at request time
     * sidesteps the ordering. Do not "simplify" this to a direct injection.
     *
     * @param appConfig the deferred handle to the typed view over {@code app.*}
     */
    @Inject
    public RequestBodyLimitFilter(final Instance<AppConfig> appConfig) {
        this.appConfig = appConfig;
    }

    @Override
    public void filter(final ContainerRequestContext requestContext) {
        final String path = requestContext.getUriInfo().getPath();
        final String contentLength = requestContext.getHeaderString(HttpHeaders.CONTENT_LENGTH);
        if (exceedsLimit(path, contentLength, appConfig.get().maxRequestBodyBytes())) {
            // Security-relevant, and low-volume (a normal client never trips it), so logged as a single line. The reported length is a parsed
            // number by the time this branch is reached, so it is safe to log verbatim.
            LOGGER.warn("Rejected {} /{} - request body of {} bytes exceeds the per-request limit of {} bytes for non-import endpoints",
                requestContext.getMethod(), path, contentLength, appConfig.get().maxRequestBodyBytes());
            requestContext.abortWith(Response
                .status(Response.Status.REQUEST_ENTITY_TOO_LARGE)
                .entity("Request body exceeds the maximum allowed size")
                .type(MediaType.TEXT_PLAIN_TYPE)
                .build());
        }
    }

    /**
     * Decides whether a request's body must be rejected as too large.
     *
     * @param path                the request path (with or without a leading slash)
     * @param contentLengthHeader the {@code Content-Length} header value, or {@code null} when absent
     * @param limitBytes          the per-request body cap in bytes; {@code 0} or less disables the check
     * @return {@code true} when the body exceeds the cap, and the path is not an exempt import endpoint
     */
    static boolean exceedsLimit(final String path, final @Nullable String contentLengthHeader, final long limitBytes) {
        if (limitBytes <= 0L || isImportPath(path)) {
            return false;
        }

        final OptionalLong contentLength = parseContentLength(contentLengthHeader);
        return contentLength.isPresent() && contentLength.getAsLong() > limitBytes;
    }

    private static boolean isImportPath(final String path) {
        final String normalised = path.startsWith("/") ? path.substring(1) : path;
        return normalised.startsWith(API_IMPORT_PATH_PREFIX) || normalised.startsWith(INTERNAL_IMPORT_PATH_PREFIX);
    }

    private static OptionalLong parseContentLength(final @Nullable String contentLengthHeader) {
        if (contentLengthHeader == null || contentLengthHeader.isBlank()) {
            return OptionalLong.empty();
        }

        try {
            return OptionalLong.of(Long.parseLong(contentLengthHeader));
        } catch (final NumberFormatException e) {
            LOGGER.trace("Error parsing content length", e);
            return OptionalLong.empty();
        }
    }
}
