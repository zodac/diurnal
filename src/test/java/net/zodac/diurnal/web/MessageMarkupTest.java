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

import org.junit.jupiter.api.Test;

/**
 * The markup composed for substitution into a message entry, which is rendered {@code .raw} and so is never escaped by Qute on the way out.
 */
class MessageMarkupTest {

    @Test
    void brandLink_composesAnExternalLinkCarryingTheProvidersName() {
        final String expected = "<a href=\"https://diurnal.example.com/oidc\" target=\"_blank\" rel=\"noopener noreferrer\" "
            + "class=\"link-brand\">Example IdP</a>";
        assertThat(MessageMarkup.brandLink("https://diurnal.example.com/oidc", "Example IdP"))
            .as("unexpected value")
            .isEqualTo(expected);
    }

    @Test
    void brandLink_escapesBothHalvesItEmbeds() {
        // The entry this is substituted into renders `.raw`, so nothing downstream escapes either value: a quote in
        // the configured issuer URL would otherwise close the href and let the rest of it write its own attributes.
        final String expected = "<a href=\"https://diurnal.example.com/&quot; onload=&quot;x\" target=\"_blank\" rel=\"noopener noreferrer\" "
            + "class=\"link-brand\">&lt;b&gt;IdP&lt;/b&gt;</a>";
        assertThat(MessageMarkup.brandLink("https://diurnal.example.com/\" onload=\"x", "<b>IdP</b>"))
            .as("unexpected value")
            .isEqualTo(expected);
    }

    @Test
    void countSpan_composesTheSpanTheClientRewritesTheCountThrough() {
        assertThat(MessageMarkup.countSpan(25L, "showing-shown"))
            .as("unexpected value")
            .isEqualTo("<span id=\"showing-shown\" class=\"js-digits\">25</span>");
    }

    @Test
    void countSpan_escapesTheIdItIsGiven() {
        assertThat(MessageMarkup.countSpan(0, "showing\"-shown"))
            .as("unexpected value")
            .isEqualTo("<span id=\"showing&quot;-shown\" class=\"js-digits\">0</span>");
    }
}
