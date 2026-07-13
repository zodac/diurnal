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

package net.zodac.diurnal.web;

import jakarta.ws.rs.core.Response;

/**
 * Factory for the HTMX error responses shared by the web resources, so the banner markup and the {@code HX-Retarget}/{@code HX-Reswap} routing are
 * built in exactly one place (they were previously duplicated per resource and could drift apart).
 */
public final class HtmxResponses {

    private HtmxResponses() {

    }

    /**
     * Builds a {@code 409 Conflict} response carrying an in-page error banner for an HTMX request. The body mirrors
     * {@code templates/partials/banner.html} (the {@code .banner*} styling defined once in {@code app.css}) so HTMX error banners match the
     * login/register pages, and the {@code HX-Retarget}/{@code HX-Reswap} headers route the swap into the page's error slot — the client opts the
     * non-2xx swap in via its {@code htmx:beforeSwap} listener (see {@code actions.js}/{@code admin-users.js}).
     *
     * @param targetSelector the CSS selector of the page's error slot (e.g. {@code #action-error}), sent as {@code HX-Retarget}
     * @param message the banner text; callers pass fixed literals or values already safe to render as HTML
     * @return the {@code 409} {@link Response} with the banner HTML entity and retarget headers
     */
    public static Response conflictBanner(final String targetSelector, final String message) {
        final String html = "<div class=\"banner banner-error\">" + message + "</div>";
        return Response.status(Response.Status.CONFLICT)
                .entity(html)
                .header("HX-Retarget", targetSelector)
                .header("HX-Reswap", "innerHTML")
                .build();
    }
}
