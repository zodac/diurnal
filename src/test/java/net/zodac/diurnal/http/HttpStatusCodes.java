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

import jakarta.ws.rs.core.Response;

/**
 * The HTTP status codes the tests assert on, as the bare {@code int} that RestAssured's {@code statusCode(int)} takes.
 *
 * <p>
 * <strong>Every value here is derived, never typed out.</strong> Each one reads its number from {@link Response.Status} — or from
 * {@link HttpStatus} for the codes JAX-RS does not define — so no status number is written as a literal anywhere in the test suite, and there is
 * nothing that can drift from the catalogue the application itself answers with. The indirection exists purely because RestAssured wants an
 * {@code int}: {@code statusCode(OK)} reads as well as {@code statusCode(200)} did, while still naming the code rather than spelling it.
 *
 * <p>
 * Static-import the constants at the call site ({@code import static net.zodac.diurnal.http.HttpStatusCodes.OK;}). Add a constant here when a test
 * needs a code this does not yet cover.
 */
public final class HttpStatusCodes { // NOPMD: DataClass - a catalogue of named constants is all this is meant to be

    /**
     * {@code 200 OK}.
     */
    public static final int OK = Response.Status.OK.getStatusCode();

    /**
     * {@code 201 Created}.
     */
    public static final int CREATED = Response.Status.CREATED.getStatusCode();

    /**
     * {@code 204 No Content}.
     */
    public static final int NO_CONTENT = Response.Status.NO_CONTENT.getStatusCode();

    /**
     * {@code 301 Moved Permanently}: accepted alongside {@link #FOUND} and {@link #SEE_OTHER} where a test asserts only that a redirect happened.
     */
    public static final int MOVED_PERMANENTLY = Response.Status.MOVED_PERMANENTLY.getStatusCode();

    /**
     * {@code 302 Found}: the browser redirect the web UI answers an unauthenticated request with.
     */
    public static final int FOUND = Response.Status.FOUND.getStatusCode();

    /**
     * {@code 303 See Other}: the post-redirect-get answer to a successful form submission.
     */
    public static final int SEE_OTHER = Response.Status.SEE_OTHER.getStatusCode();

    /**
     * {@code 304 Not Modified}: the bodiless answer to a conditional {@code GET} whose {@code If-None-Match} still matches.
     */
    public static final int NOT_MODIFIED = Response.Status.NOT_MODIFIED.getStatusCode();

    /**
     * {@code 400 Bad Request}.
     */
    public static final int BAD_REQUEST = Response.Status.BAD_REQUEST.getStatusCode();

    /**
     * {@code 401 Unauthorized}: what {@code /api/v1} answers an anonymous request with, where a page route redirects instead.
     */
    public static final int UNAUTHORIZED = Response.Status.UNAUTHORIZED.getStatusCode();

    /**
     * {@code 403 Forbidden}.
     */
    public static final int FORBIDDEN = Response.Status.FORBIDDEN.getStatusCode();

    /**
     * {@code 404 Not Found}.
     */
    public static final int NOT_FOUND = Response.Status.NOT_FOUND.getStatusCode();

    /**
     * {@code 409 Conflict}: a duplicate that a unique constraint refuses.
     */
    public static final int CONFLICT = Response.Status.CONFLICT.getStatusCode();

    /**
     * {@code 422 Unprocessable Content}: a well-formed request whose contents a validation rule refused.
     */
    public static final int UNPROCESSABLE_ENTITY = HttpStatus.UNPROCESSABLE_ENTITY.getStatusCode();

    /**
     * {@code 429 Too Many Requests}: the per-IP authentication throttle.
     */
    public static final int TOO_MANY_REQUESTS = Response.Status.TOO_MANY_REQUESTS.getStatusCode();

    /**
     * {@code 500 Internal Server Error}.
     */
    public static final int INTERNAL_SERVER_ERROR = Response.Status.INTERNAL_SERVER_ERROR.getStatusCode();

    /**
     * {@code 503 Service Unavailable}: what the readiness-gated status probe answers when the database is unreachable.
     */
    public static final int SERVICE_UNAVAILABLE = Response.Status.SERVICE_UNAVAILABLE.getStatusCode();

    private HttpStatusCodes() {

    }
}
