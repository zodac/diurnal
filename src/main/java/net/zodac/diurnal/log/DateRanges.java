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

package net.zodac.diurnal.log;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import org.jspecify.annotations.Nullable;

/**
 * Parses the mandatory {@code start}/{@code end} date-range query parameters shared by the logged-events feeds ({@code /api/v1/logs/events} and the
 * dashboard's internal minimal-events feed). Clients may send full ISO-8601 datetime strings; only the leading date part is used.
 */
final class DateRanges {

    private static final int ISO_DATE_LENGTH = 10;

    private DateRanges() {

    }

    /**
     * Parses a mandatory ISO-8601 date query parameter, rejecting a missing/blank or unparseable value with a {@link BadRequestException}.
     *
     * @param name  the query parameter's name (used in the error message)
     * @param value the raw query parameter value
     * @return the parsed {@link LocalDate}
     * @throws BadRequestException when the value is missing, blank, or not a valid ISO-8601 date
     */
    static LocalDate requireDate(final String name, final @Nullable String value) {
        if (value == null || value.isBlank()) {
            final String message = "Query parameter '" + name + "' is required";
            throw new BadRequestException(message, jsonError(message));
        }

        // Math.min instead of a length conditional: at exactly ISO_DATE_LENGTH both branches are identical, which would leave an unkillable
        // (equivalent) boundary mutant behind the 100% PIT gate.
        final String datePart = value.substring(0, Math.min(value.length(), ISO_DATE_LENGTH));
        try {
            return LocalDate.parse(datePart);
        } catch (final DateTimeParseException e) {
            final String message = "Query parameter '" + name + "' is not a valid ISO-8601 date: " + value;
            throw new BadRequestException(message, jsonError(message), e);
        }
    }

    // Carries the shared /api/v1 ApiErrorResponse JSON body on the thrown 400, so a bad date matches the error shape the OpenAPI document
    // promises (rather than falling through to RESTEasy's default body). The exception message is kept identical to the entity message.
    private static Response jsonError(final String message) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new ApiErrorResponse(message))
            .type(MediaType.APPLICATION_JSON_TYPE)
            .build();
    }
}
