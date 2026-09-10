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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ApiPages#outOfRange(int, int)}: the public API's "reject, never clamp" pagination policy, shared by every {@code /api/v1}
 * list endpoint.
 */
class ApiPagesTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3})
    void pageWithinRange_isNotRejected(final int pageNum) {
        assertThat(ApiPages.outOfRange(pageNum, 3))
            .as("page %d of 3 is a page the caller may have", pageNum)
            .isNull();
    }

    @Test
    void firstPageOfAnEmptyList_isNotRejected() {
        assertThat(ApiPages.outOfRange(1, 0))
            .as("an empty list still has a page 1, which legally returns nothing")
            .isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void pageBelowOne_isRejected(final int pageNum) {
        assertThat(statusOf(pageNum, 3))
            .as("page %d is below the first page", pageNum)
            .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void pagePastTheEnd_isRejected() {
        assertThat(statusOf(4, 3))
            .as("page 4 of 3 is past the end, and is rejected rather than clamped to page 3")
            .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void secondPageOfAnEmptyList_isRejected() {
        assertThat(statusOf(2, 0))
            .as("an empty list has exactly one page, so page 2 of it is out of range")
            .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    void rejection_namesTheRequestedPage() {
        try (final Response response = rejection(9, 3)) {
            assertThat(response.getEntity())
                .as("unexpected error payload")
                .isEqualTo(new ApiErrorResponse("Page 9 is out of range"));
        }
    }

    private static int statusOf(final int pageNum, final int totalPages) {
        try (final Response response = rejection(pageNum, totalPages)) {
            return response.getStatus();
        }
    }

    private static Response rejection(final int pageNum, final int totalPages) {
        return Objects.requireNonNull(ApiPages.outOfRange(pageNum, totalPages), "page was expected to be rejected, but was accepted");
    }
}
