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
 * The HTTP status codes this application answers with that {@link Response.Status} does not define.
 *
 * <p>
 * <strong>This enum exists only to fill gaps, never to restate the standard catalogue.</strong> {@link Response.Status} already defines 43 codes —
 * including every one this application uses except the one below — so a status that has a {@code Response.Status} constant is written with that
 * constant, and only a code missing from it is added here. Duplicating an existing constant would defeat the point: two names for one code, drifting
 * apart at the call sites that pick one or the other.
 *
 * <p>
 * It implements {@link Response.StatusType}, which is the extension point JAX-RS provides for exactly this, so a constant is handed straight to
 * {@code Response.status(...)} with no unwrapping: {@code Response.status(HttpStatus.UNPROCESSABLE_ENTITY)}. Where an API takes a bare {@code int}
 * instead (Vert.x, and the RestAssured assertions in the tests), {@link #getStatusCode()} supplies it — the same value, from the same one
 * declaration.
 *
 * <p>
 * Note that the inherited {@link Response.StatusType#toEnum()} answers {@code null} for every constant here, because it resolves through
 * {@link Response.Status#fromStatusCode(int)} and these codes are by definition absent from it. Nothing in this application calls it; a response is
 * built from the constant itself.
 */
// Both inspections fire only because there is currently exactly ONE gap to fill, which is the point of the type rather than a flaw in it: with a
// single constant the enum looks like a singleton and its constructor arguments look constant. Both stop applying the moment a second code is added.
@SuppressWarnings({"SameParameterValue", "Singleton"})
public enum HttpStatus implements Response.StatusType {

    /**
     * {@code 422 Unprocessable Content} (RFC 9110): the request was well-formed and understood, but a validation rule refused its contents. Used for
     * a rejected free-text field or a refused import archive, where {@code 400 Bad Request} would wrongly suggest the request itself was malformed.
     */
    UNPROCESSABLE_ENTITY(422, "Unprocessable Content");

    private final int statusCode;
    private final String reasonPhrase;

    HttpStatus(final int statusCode, final String reasonPhrase) {
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
    }

    @Override
    public int getStatusCode() {
        return statusCode;
    }

    @Override
    public Response.Status.Family getFamily() {
        return Response.Status.Family.familyOf(statusCode);
    }

    @Override
    public String getReasonPhrase() {
        return reasonPhrase;
    }
}
