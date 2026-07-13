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

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link HtmxResponses#conflictBanner(String, String)}: the shared {@code 409} error-banner response must carry the banner markup
 * (mirroring {@code templates/partials/banner.html}) and the {@code HX-Retarget}/{@code HX-Reswap} headers that route the swap into the caller's
 * error slot.
 */
class HtmxResponsesTest {

    @Test
    void conflictBanner_returnsConflictStatus() {
        try (Response response = HtmxResponses.conflictBanner("#action-error", "Action name cannot be empty.")) {
            assertThat(response.getStatus())
                    .as("The banner response must be a 409 Conflict")
                    .isEqualTo(Response.Status.CONFLICT.getStatusCode());
        }
    }

    @Test
    void conflictBanner_wrapsMessageInBannerMarkup() {
        try (Response response = HtmxResponses.conflictBanner("#admin-error", "Cannot delete the last administrator.")) {
            assertThat(response.getEntity())
                    .as("The entity must be the message wrapped in the shared .banner markup (mirrors partials/banner.html)")
                    .isEqualTo("<div class=\"banner banner-error\">Cannot delete the last administrator.</div>");
        }
    }

    @Test
    void conflictBanner_targetsTheCallersErrorSlot() {
        try (Response response = HtmxResponses.conflictBanner("#admin-error", "User not found.")) {
            assertThat(response.getHeaderString("HX-Retarget"))
                    .as("HX-Retarget must carry the caller's error-slot selector")
                    .isEqualTo("#admin-error");
            assertThat(response.getHeaderString("HX-Reswap"))
                    .as("HX-Reswap must replace the error slot's content")
                    .isEqualTo("innerHTML");
        }
    }
}
