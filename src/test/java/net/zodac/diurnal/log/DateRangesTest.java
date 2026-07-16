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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import jakarta.ws.rs.BadRequestException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DateRanges}: parsing of the mandatory {@code start}/{@code end} date-range query parameters.
 */
class DateRangesTest {

    @Test
    void requireDate_plainIsoDate_isParsed() {
        assertThat(DateRanges.requireDate("start", "2026-06-15"))
            .as("a plain ISO-8601 date should parse as-is")
            .isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void requireDate_isoDatetime_onlyDatePartUsed() {
        assertThat(DateRanges.requireDate("start", "2026-06-15T23:59:59"))
            .as("an ISO datetime should be truncated to its leading date part")
            .isEqualTo(LocalDate.of(2026, 6, 15));
    }

    @Test
    void requireDate_exactlyTenCharacters_isNotTruncated() {
        assertThat(DateRanges.requireDate("end", "2026-01-02"))
            .as("a value of exactly the ISO date length should be parsed whole")
            .isEqualTo(LocalDate.of(2026, 1, 2));
    }

    @Test
    void requireDate_null_throwsBadRequest() {
        assertThatExceptionOfType(BadRequestException.class)
            .as("a missing parameter should be rejected")
            .isThrownBy(() -> DateRanges.requireDate("start", null))
            .withMessageContaining("'start' is required");
    }

    @Test
    void requireDate_blank_throwsBadRequest() {
        assertThatExceptionOfType(BadRequestException.class)
            .as("a blank parameter should be rejected")
            .isThrownBy(() -> DateRanges.requireDate("end", "  "))
            .withMessageContaining("'end' is required");
    }

    @Test
    void requireDate_unparseableValue_throwsBadRequest() {
        assertThatExceptionOfType(BadRequestException.class)
            .as("a non-date value should be rejected")
            .isThrownBy(() -> DateRanges.requireDate("start", "not-a-date"))
            .withMessageContaining("not a valid ISO-8601 date");
    }
}
