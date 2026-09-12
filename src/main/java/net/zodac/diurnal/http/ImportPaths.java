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

/**
 * The data-import endpoints, named once for the two request-level guards that must agree on exactly which paths they are.
 *
 * <p>
 * The agreement is the point rather than a tidiness: import is the one capability held to a DIFFERENT body ceiling from every other endpoint
 * ({@link RequestBodyLimitFilter} exempts it so a re-imported export can use the larger {@code quarkus.http.limits.max-body-size}), and it is
 * therefore also the one capability whose concurrency has to be bounded instead ({@link ImportConcurrencyFilter}). A path that drifted out of one
 * set and not the other would be either capped twice or bounded by neither, and the second of those is silent.
 */
final class ImportPaths {

    private static final String API_IMPORT_PATH_PREFIX = "api/v1/data/import";
    private static final String INTERNAL_IMPORT_PATH_PREFIX = "internal/data/import";

    private ImportPaths() {

    }

    /**
     * Whether the given request path is one of the data-import endpoints - {@code /api/v1/data/import}, {@code /internal/data/import} or either
     * one's {@code /preview}.
     *
     * <p>
     * A prefix match rather than an equality one, so both the commit and the preview endpoint of each surface are covered by a single constant. The
     * leading slash is optional: a JAX-RS {@code UriInfo} path arrives without one and a Vert.x {@code request().path()} with one, and this is
     * consulted from both.
     *
     * @param path the request path, with or without a leading slash
     * @return {@code true} when the path is a data-import endpoint
     */
    static boolean isImportPath(final String path) {
        final String normalised = path.startsWith("/") ? path.substring(1) : path;
        return normalised.startsWith(API_IMPORT_PATH_PREFIX) || normalised.startsWith(INTERNAL_IMPORT_PATH_PREFIX);
    }
}
