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
import org.jspecify.annotations.Nullable;

/**
 * The public API's pagination surface policy, in the one place every {@code /api/v1} list endpoint reads it from.
 *
 * <p>
 * <strong>An out-of-range page is REJECTED here, never clamped</strong> — the opposite of what {@link net.zodac.diurnal.page.Pages} does for a web
 * surface, and deliberately so: a page number handed to the API is answered with that page or with a {@code 400}, never silently with some other
 * page. Page 1 of an empty list is legal and returns nothing. Every list endpoint previously wrote this same five-line guard out for itself.
 */
public final class ApiPages {

    private ApiPages() {

    }

    /**
     * The {@code 400} for a page number outside the list's range, or {@code null} when the requested page is one the caller may have.
     *
     * @param pageNum    the requested 1-based page
     * @param totalPages the number of pages the list spans ({@code 0} when it is empty)
     * @return the rejection response, or {@code null} when the page is in range
     */
    @Nullable
    public static Response outOfRange(final int pageNum, final int totalPages) {
        if (pageNum >= 1 && pageNum <= Math.max(1, totalPages)) {
            return null;
        }
        return ApiErrorResponse.badRequest("Page " + pageNum + " is out of range");
    }
}
