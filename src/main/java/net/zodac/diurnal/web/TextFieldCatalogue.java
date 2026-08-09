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
 *
 * <p>
 * <strong>Nothing here has a Java caller.</strong> Qute resolves every accessor by NAME at render time, so an IDE's unused-declaration inspection
 * flags all of them; each one therefore names the templates that read it, and carries {@code @SuppressWarnings("unused")} the way the CDI observers
 * elsewhere in this package do. Deleting one compiles cleanly and fails at render, so the pointers below are what stands in for a compiler check.
 */
@Named("textFields")
@ApplicationScoped
public class TextFieldCatalogue {

    /**
     * The action-name field, bounding the new-action input on {@code actions.html} and the rename input on {@code partials/action-row.html}.
     *
     * @return the field specification
     */
    @SuppressWarnings("unused") // read by name from actions.html + partials/action-row.html; no Java caller
    public TextField actionName() {
        return TextFields.ACTION_NAME;
    }

    /**
     * The display-name field, bounding the registration form's display-name input and the Settings account card's.
     *
     * @return the field specification
     */
    @SuppressWarnings("unused") // read by name from register.html + settings.html; no Java caller
    public TextField displayName() {
        return TextFields.DISPLAY_NAME;
    }

    /**
     * The stat-name field, bounding the rename input on each Settings stats-picker row.
     *
     * @return the field specification
     */
    @SuppressWarnings("unused") // read by name from settings.html (the stats-picker row); no Java caller
    public TextField statName() {
        return TextFields.STAT_NAME;
    }

    /**
     * The email field, bounding the registration form's email input.
     *
     * @return the field specification
     */
    @SuppressWarnings("unused") // read by name from register.html; no Java caller
    public TextField email() {
        return TextFields.EMAIL;
    }

    /**
     * The password field, bounding every password input (registration and its confirmation, the Settings change-password steps) and supplying the
     * requirements tooltip its constraints are listed in.
     *
     * @return the field specification
     */
    @SuppressWarnings("unused") // read by name from register.html, settings.html + partials/password-constraints.html; no Java caller
    public TextField password() {
        return TextFields.PASSWORD;
    }

    /**
     * The day-note field, bounding the dashboard note box (read as its {@code data-note-max} attribute, which note.js enforces client-side).
     *
     * @return the field specification
     */
    @SuppressWarnings("unused") // read by name from dashboard.html; no Java caller
    public TextField note() {
        return TextFields.NOTE;
    }
}
