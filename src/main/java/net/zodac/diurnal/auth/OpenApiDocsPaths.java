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

package net.zodac.diurnal.auth;

/**
 * The path patterns identifying the admin-gated OpenAPI documentation surface (the Swagger UI shell
 * and the generated OpenAPI document). Shared between {@link OpenApiDocsAuthFilter} (which enforces
 * the admin-only access gate on these paths) and {@code net.zodac.diurnal.web.CspPolicy} (which
 * relaxes the {@code Content-Security-Policy} on the same paths, since Swagger UI cannot run under the
 * app's strict policy) so the two path matches cannot drift apart.
 */
public final class OpenApiDocsPaths {

    /**
     * The Swagger UI shell: {@code /api}, {@code /api/} and {@code /api/index.html} only — NOT the
     * JAX-RS endpoints under {@code /api/...} (e.g. {@code /api/auth/login}), which carry further path
     * segments and must stay reachable under the strict policy.
     */
    public static final String SWAGGER_UI_PATH_REGEX = "^/api(/|/index\\.html)?$";

    /**
     * The generated OpenAPI document and its {@code .json} / {@code .yaml} variants.
     */
    public static final String OPENAPI_DOCUMENT_PATH_REGEX = "^/q/openapi(\\..*)?$";

    private OpenApiDocsPaths() {

    }
}
