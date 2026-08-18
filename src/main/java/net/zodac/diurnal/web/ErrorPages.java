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
import io.quarkus.qute.i18n.MessageBundles;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.zodac.diurnal.user.Font;
import net.zodac.diurnal.user.Language;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.Theme;
import org.jspecify.annotations.Nullable;

/**
 * Builds the styled full-page error responses (403/404) shared by the exception mappers, so the identity-to-template-data extraction and the default
 * theme/font wiring live in exactly one place rather than being duplicated (and able to drift) per mapper.
 */
final class ErrorPages {

    private ErrorPages() {

    }

    /**
     * Renders a styled error page for the given status, filling the header with the signed-in user's display name and admin flag (or blanks for an
     * anonymous visitor). The default theme/font are used because an error page is rendered without loading the user's saved preferences — unlike
     * language, which is resolved from the request's {@code Accept-Language} header rather than a fixed default, since an error page is one of the
     * "no session yet" surfaces {@code Language.fromAcceptLanguageHeader} exists for (see its Javadoc).
     *
     * @param template          the error-page Qute template (e.g. the {@code error-404} template)
     * @param status            the HTTP status to return
     * @param identity          the current security identity (possibly anonymous)
     * @param acceptLanguage    the request's raw {@code Accept-Language} header, or {@code null}
     * @return the {@code text/html} {@link Response} carrying the rendered page
     */
    static Response render(final Template template, final Response.Status status, final SecurityIdentity identity,
        final @Nullable String acceptLanguage) {
        // Read displayName and isAdmin from the identity attributes - set at auth time by
        // UserIdentities (session auth) / OidcUserProvisioner, so no DB call is needed here.
        String displayName = "";
        boolean isAdmin = false;
        if (!identity.isAnonymous()) {
            final String attr = identity.getAttribute("displayName");
            displayName = attr != null ? attr : identity.getPrincipal().getName();
            isAdmin = identity.hasRole(Role.Values.ADMIN_INTERNAL_VALUE);
        }
        final Language language = Language.fromAcceptLanguageHeader(acceptLanguage);
        return Response.status(status)
                .entity(template
                        .data("theme", Theme.DEFAULT.value())
                        .data("font", Font.DEFAULT.value())
                        .data("language", language.value())
                        .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, language.locale())
                        .data("displayName", displayName)
                        .data("isAdmin", isAdmin))
                .type(MediaType.TEXT_HTML_TYPE)
                .build();
    }
}
