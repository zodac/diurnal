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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link HtmlEscaping}, whose output must match what Qute's own escaper produces for the same text - the two are used on the same
 * page, on either side of a {@code .raw} (see that class' Javadoc).
 */
class HtmlEscapingTest {

    @ParameterizedTest
    // The delimiter is a pipe rather than the conventional comma: every expected value here ends its entities with a semicolon and holds a comma
    // in none of them, so a `;` delimiter silently truncates each one at the first entity.
    @CsvSource(delimiter = '|', value = {
        "Tea & Coffee|Tea &amp; Coffee",
        "<script>|&lt;script&gt;",
        "a > b|a &gt; b",
        "say \"this\"|say &quot;this&quot;",
        "it's mine|it&#39;s mine"
    })
    void escapeHtml_replacesEveryCharacterThatWouldReadAsMarkup(final String value, final String expected) {
        assertThat(HtmlEscaping.escapeHtml(value))
            .as("'%s' should be escaped exactly as Qute's own HtmlEscaper escapes it", value)
            .isEqualTo(expected);
    }

    @Test
    void escapeHtml_escapesEveryOccurrence_notJustTheFirst() {
        assertThat(HtmlEscaping.escapeHtml("<b>Tea & Coffee & Cocoa</b>"))
            .as("every occurrence must be replaced - one missed character is a tag reaching the page")
            .isEqualTo("&lt;b&gt;Tea &amp; Coffee &amp; Cocoa&lt;/b&gt;");
    }

    @Test
    void escapeHtml_leavesTextHoldingNoneOfThemUntouched() {
        // Including non-ASCII, which is escaped by nothing here: the page is UTF-8, and a note or action name is routinely written in another script.
        assertThat(HtmlEscaping.escapeHtml("Ir a nadar - 泳ぐ - سباحة"))
            .as("text with nothing to escape must come back exactly as it went in")
            .isEqualTo("Ir a nadar - 泳ぐ - سباحة");
    }

    @Test
    void escapeHtml_handlesTextWithNoCharactersAtAll() {
        assertThat(HtmlEscaping.escapeHtml(String.valueOf(new char[0])))
            .as("an empty value is not a special case - it escapes to itself")
            .isEmpty();
    }
}
