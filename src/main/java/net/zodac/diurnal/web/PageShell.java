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

import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.i18n.MessageBundles;
import net.zodac.diurnal.user.Font;
import net.zodac.diurnal.user.Language;
import net.zodac.diurnal.user.Theme;
import net.zodac.diurnal.user.User;

/**
 * The data every full page render needs before it adds anything of its own: what {@code layout.html} and {@code partials/navbar.html} read off any
 * page regardless of which one it is — the viewer's identity, the appearance preferences that become {@code <html>}'s attributes, and the locale the
 * whole render's {@code {msg:...}} lookups resolve against.
 *
 * <p>
 * Written out once per page-rendering resource before this existed, which made the {@code MessageBundles.ATTRIBUTE_LOCALE} attribute the risk it is:
 * a page that omits it does not fail, it silently renders in English. Setting it beside the {@code language} it must agree with is what stops the two
 * drifting apart.
 */
public final class PageShell {

    private PageShell() {

    }

    /**
     * The shell for a signed-in page: the viewer's identity and appearance preferences, with the render's locale bound to the same
     * {@link User#language} the page renders into {@code <html lang>}.
     *
     * @param template the page template
     * @param user     the signed-in viewer
     * @return the started template instance, for the page to add its own data to
     */
    public static TemplateInstance forUser(final Template template, final User user) {
        return forUser(template.instance(), user);
    }

    /**
     * The {@link #forUser(Template, User)} shell applied to an instance a caller has already started — the dashboard, whose stats-summary strip is
     * seeded before the shell is.
     *
     * @param instance the template instance under construction
     * @param user     the signed-in viewer
     * @return the same instance, for the page to add its own data to
     */
    public static TemplateInstance forUser(final TemplateInstance instance, final User user) {
        return instance
            .data("email", user.email)
            .data("displayName", user.displayName)
            .data("isAdmin", user.isAdmin())
            .data("theme", user.theme)
            .data("font", user.font)
            .data("language", user.language)
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, user.locale());
    }

    /**
     * The shell for a page rendered with no session yet — login, register, the first-run setup wizard, the error pages. There are no stored
     * preferences to read, so the appearance is the default pair and the language is the one negotiated from the request's {@code Accept-Language}.
     *
     * @param template the page template
     * @param language the language negotiated for this request
     * @return the started template instance, for the page to add its own data to
     */
    public static TemplateInstance anonymous(final Template template, final Language language) {
        return template.instance()
            .data("theme", Theme.DEFAULT.value())
            .data("font", Font.DEFAULT.value())
            .data("language", language.value())
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, language.locale());
    }
}
