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

import java.util.Optional;
import net.zodac.diurnal.user.User;

/**
 * The pure authorisation decision for the OpenAPI documentation surface (Swagger UI + the generated
 * OpenAPI document): only an authenticated administrator may see it. Kept free of any Vert.x/HTTP
 * types so the branching is unit-testable; {@link OpenApiDocsAuthFilter} owns the glue that resolves
 * the session and applies the outcome.
 */
final class OpenApiDocsAccess {

    private OpenApiDocsAccess() {

    }

    /**
     * Decides how a documentation request must be handled from the user resolved against its session
     * token: served for an administrator, redirected to login when anonymous, and forbidden for an
     * authenticated non-administrator.
     *
     * @param resolvedUser the user the request's session token resolved to, or empty if anonymous
     * @return the {@link Outcome} the filter must apply
     */
    static Outcome decide(final Optional<User> resolvedUser) {
        return resolvedUser
            .map(user -> user.isAdmin() ? Outcome.ALLOW : Outcome.FORBIDDEN)
            .orElse(Outcome.REDIRECT_TO_LOGIN);
    }

    /**
     * The action the filter must take for a documentation request.
     */
    enum Outcome {

        /**
         * Serve the documentation — the requester is an authenticated administrator.
         */
        ALLOW,

        /**
         * Redirect the requester to the login page — no valid session was presented.
         */
        REDIRECT_TO_LOGIN,

        /**
         * Deny with a {@code 403} — the requester is authenticated but is not an administrator.
         */
        FORBIDDEN
    }
}
