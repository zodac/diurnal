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

package net.zodac.diurnal.openapi;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The error payload every {@code /api/v1/*} endpoint returns for a rejected request, so API clients can rely on one error shape across the whole
 * public surface.
 *
 * @param message human-readable description of the error
 */
@Schema(description = "Error payload returned when an API request is rejected.")
public record ApiErrorResponse(
    @Schema(examples = "An action named 'Running' already exists", description = "Human-readable description of the error.") String message) {

    /**
     * The {@code 400} every rejected {@code /api/v1} request answers with, wrapping {@code message} in this payload — so the status and the body
     * shape are decided here rather than restated at each resource that rejects something.
     *
     * @param message the human-readable rejection wording (English by design; see {@code NotUiFacing})
     * @return the {@code 400} response
     */
    public static Response badRequest(final String message) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new ApiErrorResponse(message))
            .build();
    }
}
