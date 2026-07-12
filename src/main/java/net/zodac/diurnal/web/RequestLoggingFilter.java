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
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Logs every REST endpoint hit at {@code TRACE}, giving an administrator per-request visibility of the
 * traffic reaching the application without instrumenting each resource method.
 *
 * <p>As a JAX-RS provider it fires only for requests that reach a resource method — static assets and
 * framework-served paths (the Swagger UI shell, health checks) are handled outside JAX-RS and are not
 * logged here. On the way in it records {@code method + path}; on the way out it records the resolved
 * status and the wall-clock time spent in the resource, so a slow or failing endpoint is visible at a
 * glance. Everything is emitted at {@code TRACE}, so it stays silent in production (root
 * {@code net.zodac.diurnal} defaults to {@code INFO}) until {@code LOG_LEVEL=TRACE} turns it on.
 *
 * <p>High-frequency infrastructure probes are excluded entirely (at every level): the container's
 * Docker {@code HEALTHCHECK} hammers {@code /health} on a short interval, which would otherwise drown
 * the trace stream in self-generated noise. Those paths ({@link #UNLOGGED_PATHS}) are never logged.
 */
@Provider
@Priority(Integer.MAX_VALUE) // Innermost request filter / outermost response filter, so the timing brackets the resource method as tightly as possible.
public class RequestLoggingFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger LOGGER = LogManager.getLogger(RequestLoggingFilter.class);
    private static final String START_NANOS_PROPERTY = "net.zodac.diurnal.requestStartNanos";

    // Infrastructure probe paths (Docker HEALTHCHECK etc.) that are never logged, at any level.
    private static final Set<String> UNLOGGED_PATHS = Set.of("health");

    @Override
    public void filter(final ContainerRequestContext requestContext) {
        if (!LOGGER.isTraceEnabled() || !shouldLog(requestContext.getUriInfo().getPath())) {
            return;
        }
        requestContext.setProperty(START_NANOS_PROPERTY, System.nanoTime());
        LOGGER.trace("--> {} /{}", requestContext.getMethod(), requestContext.getUriInfo().getPath());
    }

    @Override
    public void filter(final ContainerRequestContext requestContext, final ContainerResponseContext responseContext) {
        if (!LOGGER.isTraceEnabled() || !shouldLog(requestContext.getUriInfo().getPath())) {
            return;
        }
        LOGGER.trace("<-- {} /{} {} ({}ms)",
            requestContext.getMethod(),
            requestContext.getUriInfo().getPath(),
            responseContext.getStatus(),
            elapsedMillis(requestContext.getProperty(START_NANOS_PROPERTY)));
    }

    /**
     * Renders the elapsed time since a request-start {@code nanoTime} marker as whole milliseconds.
     *
     * @param startNanos the {@link System#nanoTime()} value stashed when the request arrived, or
     *                   {@code null}/a non-{@code Long} when it could not be recorded
     * @return the elapsed whole milliseconds as a string, or {@code "?"} when no start marker is present
     */
    static String elapsedMillis(final @Nullable Object startNanos) {
        if (!(startNanos instanceof final Long start)) {
            return "?";
        }
        return Long.toString((System.nanoTime() - start) / 1_000_000L);
    }

    /**
     * Decides whether a request path should be logged, excluding high-frequency infrastructure probes.
     *
     * @param path the request path from {@code UriInfo.getPath()} (with or without a leading slash)
     * @return {@code false} for a path in {@link #UNLOGGED_PATHS} (e.g. the container health check),
     *         {@code true} otherwise
     */
    static boolean shouldLog(final String path) {
        final String normalised = path.startsWith("/") ? path.substring(1) : path;
        return !UNLOGGED_PATHS.contains(normalised);
    }
}
