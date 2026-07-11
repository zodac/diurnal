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

import net.zodac.diurnal.auth.OpenApiDocsPaths;

/**
 * The pure {@code Content-Security-Policy} decision for a request path. Almost every route gets the
 * app's strict policy; the admin-gated OpenAPI documentation surface (Swagger UI shell, generated
 * OpenAPI document) cannot run under it — Swagger UI bootstraps itself with inline script — so those
 * two paths get a separate, relaxed policy instead. Kept free of any Vert.x/HTTP types so the
 * branching is unit-testable; {@link SecurityHeadersFilter} owns the glue that applies the result.
 *
 * <p>{@code style-src-attr 'unsafe-inline'} is the strict policy's one deliberate laxity:
 * the app renders per-user swatch colours as inline {@code style="…"}
 * attributes, which — unlike the FOUC script/style — can't be pinned to a static hash since the
 * colour varies per action at request time.
 *
 * <p>{@code default-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'}
 * close the remaining fetch-destination directives on the strict policy:
 * an audit of every template, stylesheet, and served script found no {@code data:} URI consumer (all
 * images/favicons are real same-origin files), no font source outside the bundled {@code /fonts/}
 * woff2 files, and no fetch/HTMX target outside the app's own origin — so none of the three need the
 * relaxations the docs policy below carries, and {@code default-src} closes off any directive this
 * class doesn't otherwise set.
 */
final class CspPolicy {

    /*
     * The FOUC theme bootstrap in layout.html:<head> is the only inline script left in the app,
     * and it is byte-static (it reads its argument from the `data-theme`
     * attribute on <html> rather than having Qute interpolate it), so a single pinned hash covers it on
     * every render. SecurityHeadersFilterIT re-derives this hash from a live page fetch and asserts it
     * matches — any edit to that script's bytes must update this constant together with that test.
     */
    private static final String FOUC_SCRIPT_HASH = "'sha256-cXgqQs2hN+sIaWJZiyQ8nLMT+YhCIxMlf9gdREXl5Dc='";

    private static final String FOUC_STYLE_HASH = "'sha256-WHwC8VvEmvZsRHoS2m0MfUQCxGI/3W3YZCHd4BgZ9B4='";

    private static final String STRICT_POLICY =
        "default-src 'self'; frame-ancestors 'self'; base-uri 'self'; form-action 'self'; object-src 'none'; "
            + "script-src 'self' " + FOUC_SCRIPT_HASH + "; script-src-attr 'none'; "
            + "style-src 'self' " + FOUC_STYLE_HASH + "; style-src-attr 'unsafe-inline'; "
            + "img-src 'self'; font-src 'self'; connect-src 'self'";

    /*
     * Swagger UI (served by Quarkus's smallrye-openapi-ui) bootstraps itself with an inline script and
     * inline styles, and cannot be made to run under the strict policy above without rewriting a
     * third-party dependency. Both surfaces are already admin-gated by OpenApiDocsAuthFilter, so a
     * relaxed policy here only ever reaches an authenticated administrator.
     */
    private static final String DOCS_POLICY =
        "frame-ancestors 'self'; base-uri 'self'; form-action 'self'; object-src 'none'; "
            + "script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:";

    private CspPolicy() {

    }

    /**
     * Decides which {@code Content-Security-Policy} directive string applies to a request path.
     *
     * @param path the request path, e.g. {@code ctx.request().path()}
     * @return the relaxed documentation policy for the Swagger UI shell or the generated OpenAPI
     *     document, otherwise the app's strict policy
     */
    static String forPath(final String path) {
        if (path.matches(OpenApiDocsPaths.SWAGGER_UI_PATH_REGEX) || path.matches(OpenApiDocsPaths.OPENAPI_DOCUMENT_PATH_REGEX)) {
            return DOCS_POLICY;
        }
        return STRICT_POLICY;
    }
}
