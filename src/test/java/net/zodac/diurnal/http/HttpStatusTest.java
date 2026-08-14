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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HttpStatus}: the status metadata each constant carries, that a constant can be handed straight to a JAX-RS response builder,
 * and the invariant that keeps this enum a set of gaps rather than a second catalogue.
 */
class HttpStatusTest {

    @Test
    void unprocessableEntity_carriesItsStatusMetadata() {
        assertThat(HttpStatus.UNPROCESSABLE_ENTITY.getStatusCode())
            .as("unexpected status code")
            .isEqualTo(422);
        assertThat(HttpStatus.UNPROCESSABLE_ENTITY.getFamily())
            .as("422 is a client error, and the family is what a caller filters responses on")
            .isEqualTo(Response.Status.Family.CLIENT_ERROR);
        assertThat(HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase())
            .as("unexpected reason phrase")
            .isEqualTo("Unprocessable Content");
    }

    @Test
    void constant_buildsResponseDirectly() {
        // The whole point of implementing Response.StatusType: no unwrapping to an int at the call site.
        try (final Response response = Response.status(HttpStatus.UNPROCESSABLE_ENTITY).build()) {
            assertThat(response.getStatus())
                .as("the response must carry the constant's own code")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY.getStatusCode());
        }
    }

    @Test
    void everyConstant_isAbsentFromResponseStatus() {
        for (final HttpStatus status : HttpStatus.values()) {
            assertThat(Response.Status.fromStatusCode(status.getStatusCode()))
                .as("%s duplicates a jakarta.ws.rs Response.Status constant, so it must be deleted and that constant used instead", status)
                .isNull();
        }
    }
}
