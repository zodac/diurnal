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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import net.zodac.diurnal.text.TextField;
import net.zodac.diurnal.text.TextFields;

/**
 * Exposes the {@link TextFields} catalogue to every template as {@code {inject:textFields}}, so an input's {@code maxlength} attribute and its
 * requirements tooltip are rendered from the same constant the server validates against.
 *
 * <p>
 * Kept as a {@code @Named} bean rather than threaded through each page's data map because the inputs it bounds are spread across pages and HTMX
 * partials (the action row, the settings cards, the registration form) that no single resource renders.
 */
@Named("textFields")
@ApplicationScoped
public class TextFieldCatalogue {

    /**
     * The action-name field.
     *
     * @return the field specification
     */
    public TextField actionName() {
        return TextFields.ACTION_NAME;
    }

    /**
     * The display-name field.
     *
     * @return the field specification
     */
    public TextField displayName() {
        return TextFields.DISPLAY_NAME;
    }

    /**
     * The stat-name field.
     *
     * @return the field specification
     */
    public TextField statName() {
        return TextFields.STAT_NAME;
    }

    /**
     * The email field.
     *
     * @return the field specification
     */
    public TextField email() {
        return TextFields.EMAIL;
    }

    /**
     * The password field.
     *
     * @return the field specification
     */
    public TextField password() {
        return TextFields.PASSWORD;
    }

    /**
     * The day-note field.
     *
     * @return the field specification
     */
    public TextField note() {
        return TextFields.NOTE;
    }
}
