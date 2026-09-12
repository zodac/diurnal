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

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

/**
 * Stamps {@code Cache-Control: private, no-store} on every {@code /api/v1} response that does not already carry a caching directive.
 *
 * <p>
 * <strong>No response filter configured in {@code application.properties} touches {@code /api}.</strong> The {@code html-pages} filter that marks
 * every page {@code no-store} excludes it by name, and the {@code html-fragments} filter covers only {@code /internal}. So an API response carries
 * exactly the directive its resource method attached - and a {@code 200} carrying none at all is heuristically cacheable by any cache that sees it,
 * the browser's own disk cache included.
 *
 * <p>
 * <strong>Every one of these responses is private per-user data</strong>, which is the same reason {@link EntityTags} attaches
 * {@code private, no-cache} to the validated reads. That directive was applied per call site, so it reached only the endpoints that happened to want
 * an {@code ETag}; the rest - the whole-journal export among them, which is the one artefact holding every note IN THE CLEAR - went out undirected.
 * Applying the rule here instead makes it structural: a new API read is covered by existing, rather than by remembering.
 *
 * <p>
 * {@code no-store} rather than {@code no-cache}, because these are the responses with no validator to revalidate against - there is nothing to be
 * gained by a cache keeping a copy it must always re-fetch. An endpoint that DOES set its own directive is left alone: {@link EntityTags}' reads need
 * the browser to keep a stored copy for their {@code ETag} to earn a {@code 304}, so overwriting their {@code private, no-cache} with
 * {@code no-store} would quietly undo the conditional-GET support.
 */
@Provider
public class ApiCacheHeadersFilter implements ContainerResponseFilter {

    private static final String API_PATH_PREFIX = "api/v1";
    private static final String PRIVATE_NO_STORE = "private, no-store";

    @Override
    public void filter(final ContainerRequestContext requestContext, final ContainerResponseContext responseContext) {
        final boolean alreadyDirected = responseContext.getHeaders().containsKey(HttpHeaders.CACHE_CONTROL);
        if (needsPrivateDirective(requestContext.getUriInfo().getPath(), alreadyDirected)) {
            responseContext.getHeaders().add(HttpHeaders.CACHE_CONTROL, PRIVATE_NO_STORE);
        }
    }

    /**
     * Decides whether a response must be given the default private directive: it is an {@code /api/v1} response, and its resource method attached no
     * caching directive of its own.
     *
     * @param path            the request path (with or without a leading slash)
     * @param alreadyDirected whether the response already carries a {@code Cache-Control} header
     * @return {@code true} when the default directive must be added
     */
    static boolean needsPrivateDirective(final String path, final boolean alreadyDirected) {
        if (alreadyDirected) {
            return false;
        }

        final String normalised = path.startsWith("/") ? path.substring(1) : path;
        return normalised.startsWith(API_PATH_PREFIX);
    }
}
