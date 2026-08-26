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
 * HTML-escaping for the few template expressions that are deliberately rendered as MARKUP rather than as a value.
 *
 * <p>
 * Qute escapes the value of every expression in an {@code .html} template, and the rendered result of a {@code {msg:...}} one is such a value - so
 * an entry whose wording carries markup (the bold CSV filenames in the import-refusal sentences, {@code .claude/I18N.md}) shows its tags as literal
 * angle brackets unless the arm rendering it asks for {@code .raw}. That switches the escaping off for everything the entry rendered, and a message
 * bundle's own template carries no content type, so Qute never escaped the parameters substituted into it either: {@code .raw} on an entry that
 * embeds text out of an upload would put that text on the page as markup. Escaping such a parameter on the way in -
 * {@code {actionName.escapeHtml}} - is what this exists for.
 *
 * <p>
 * <strong>Only ever use it beside a {@code .raw}.</strong> Everywhere else Qute has already escaped the value, and a second pass shows the entities
 * themselves ({@code &amp;lt;} for a {@code <}). The five replacements are exactly the ones Qute's own {@code HtmlEscaper} makes, so a value escaped
 * here is indistinguishable from one escaped there.
 */
public final class HtmlEscaping {

    private HtmlEscaping() {

    }

    /**
     * Escapes the five characters that would otherwise be read as markup: {@code &}, {@code <}, {@code >}, {@code "} and {@code '}.
     *
     * @param value the text to escape
     * @return the escaped text, unchanged when it holds none of those characters
     */
    @TemplateExtension
    public static String escapeHtml(final String value) {
        final int length = value.length();
        final StringBuilder escaped = new StringBuilder(length);

        for (int i = 0; i < length; i++) {
            final char character = value.charAt(i);
            switch (character) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(character);
            }
        }

        return escaped.toString();
    }
}
