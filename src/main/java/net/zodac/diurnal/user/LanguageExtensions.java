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

package net.zodac.diurnal.user;

import io.quarkus.qute.TemplateExtension;

/**
 * A template-facing bridge from the plain {@code language} value every page already renders (the raw
 * {@code User.language}/{@code Language#value()} string, e.g. {@code "ar-SA"}) to {@link Language#dir()} - kept out
 * of {@link Language} itself since every OTHER method there is called from plain Java, and this one exists solely
 * so {@code templates/layout.html} can resolve {@code <html dir="...">} without every page-rendering resource also
 * threading a separate {@code dir} value alongside the {@code language} string it already sets.
 */
public final class LanguageExtensions {

    private LanguageExtensions() {

    }

    /**
     * Resolves the writing direction for a raw {@code language} value.
     *
     * @param language the stored/rendered language value (e.g. {@code "ar-SA"})
     * @return {@code "rtl"} for a right-to-left language, otherwise {@code "ltr"}
     */
    @TemplateExtension(namespace = "language")
    public static String dir(final String language) {
        return Language.fromValue(language).dir();
    }
}
