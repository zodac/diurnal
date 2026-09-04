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

import io.quarkus.qute.TemplateExtension;

/**
 * The elements a sentence wraps, composed so that the sentence itself can stay ONE bundle entry.
 *
 * <p>
 * A sentence with an element in the middle of it - a linked provider name, a live counter - used to be written as two or three entries the template
 * set on either side of that element. That shape cannot be translated: it fixes the element's position for every language at the position English
 * puts it in, and the fragments are meaningless in isolation to whoever is translating them. The entry instead carries a {@code {placeholder}} and
 * the element arrives already composed, exactly as {@code app.js} substitutes the live clock into {@link AppMessages#lockoutRetryCountdown(String)}
 * on the client. Where the element is FIXED markup the template passes a string literal directly and needs nothing from here; these are the two that
 * carry a value and so have to be built.
 *
 * <p>
 * Neither is NAMESPACED, and that is load-bearing rather than a style choice: a {@code ns:method(...)} call does not resolve when it is nested
 * inside a message parameter - the bundle resolver is handed a {@code Results$NotFound} and the render dies with a {@code ClassCastException},
 * which the BUILD does not catch, since template analysis validates the call itself perfectly well. The plain base-object form nested the same way
 * is what {@code import-reason.html}'s {@code {msg:importUnknownAction(actionName.escapeHtml)}} already relies on.
 *
 * <p>
 * Every method returns MARKUP, so the entry it is substituted into is rendered {@code .raw} - which switches Qute's escaping off for the whole
 * entry, including this markup's own arguments (see {@link HtmlEscaping}). Each therefore escapes what it embeds here rather than relying on the
 * call site to remember, since the point of substituting a whole element is that the call site never sees the two halves separately.
 */
@TemplateExtension
public final class MessageMarkup {

    private MessageMarkup() {

    }

    /**
     * Composes the brand-coloured external link a sentence names an identity provider with.
     *
     * @param url  the address the name links to - the extension's base, so the call reads {@code {issuerUrl.brandLink(name)}}
     * @param text the provider's display name
     * @return the composed {@code <a>} element
     */
    public static String brandLink(final String url, final String text) {
        return "<a href=\"" + HtmlEscaping.escapeHtml(url) + "\" target=\"_blank\" rel=\"noopener noreferrer\" class=\"link-brand\">"
            + HtmlEscaping.escapeHtml(text) + "</a>";
    }

    /**
     * Composes one of the two counts in a list footer's "Showing x of y". The {@code id} is what lets {@code actions.js} rewrite the number in place
     * after a surgical add or delete, and {@code .js-digits} is what renders it in the viewer's own digit glyphs.
     *
     * @param value the count to show - the extension's base, so the call reads {@code {page.totalCount.countSpan('showing-total')}}
     * @param id    the element id the client rewrites the count through
     * @return the composed {@code <span>} element
     */
    public static String countSpan(final Object value, final String id) {
        return "<span id=\"" + HtmlEscaping.escapeHtml(id) + "\" class=\"js-digits\">" + value + "</span>";
    }
}
