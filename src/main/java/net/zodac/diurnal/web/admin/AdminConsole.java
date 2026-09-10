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

package net.zodac.diurnal.web.admin;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.i18n.MessageBundles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import net.zodac.diurnal.user.Language;
import net.zodac.diurnal.web.HtmxResponses;

/**
 * The two things every admin-console surface needs and neither owns: the banner wording shared by the users list and the IP-lockout table, and the
 * timestamp format both of their date cells render in. Both were previously written out once per resource — identically, with the second copy's
 * comment pointing at the first — which is exactly the shape that lets an admin read two tables on the same page formatted two different ways.
 */
@ApplicationScoped
public class AdminConsole {

    private static final String ERROR_BANNER_TARGET = "#admin-error";

    private final Template adminMessagesTemplate;

    /**
     * Injects the shared admin banner/prompt message partial.
     *
     * @param adminMessagesTemplate the fixed-shape admin banner/prompt message partial template
     */
    @Inject
    public AdminConsole(@Location("partials/admin-messages") final Template adminMessagesTemplate) {
        this.adminMessagesTemplate = adminMessagesTemplate;
    }

    /**
     * Renders one of the fixed-shape admin banners, translated for the viewing administrator.
     *
     * @param key    the banner's key, switched on inside {@code partials/admin-messages.html}
     * @param locale the viewing administrator's locale
     * @return the rendered banner text
     */
    public String banner(final String key, final Locale locale) {
        return adminMessagesTemplate.data("key", key).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
    }

    /**
     * The {@code 409} HTMX response carrying one of the fixed-shape admin banners, retargeted into the admin console's own error slot — the shape
     * every rejected admin mutation answers with, on both of the console's tables.
     *
     * @param key    the banner's key, switched on inside {@code partials/admin-messages.html}
     * @param locale the viewing administrator's locale
     * @return the {@code 409} response with the retargeted banner
     */
    public Response errorBanner(final String key, final Locale locale) {
        return HtmxResponses.conflictBanner(ERROR_BANNER_TARGET, banner(key, locale));
    }

    /**
     * The format every admin-console timestamp is rendered in, localised for the viewing administrator like every other date the app renders: a fixed
     * {@code "yyyy-MM-dd HH:mm"} in {@code Locale.ROOT} pinned the field order, forced 24-hour regardless of the language's own hour cycle, and
     * emitted ASCII digits on a page whose every other number was in the language's own glyphs. MEDIUM (not SHORT) for the date, because SHORT
     * abbreviates the year to two digits and "9/5/26" reads as two different days in en-GB and en-US - an admin comparing this against a log needs it
     * unambiguous.
     *
     * @param zone     the viewing administrator's timezone
     * @param language the viewing administrator's language
     * @return the timestamp formatter
     */
    public static DateTimeFormatter timestampFormatter(final ZoneId zone, final Language language) {
        return language.localizeNumerals(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(language.locale())
            .withZone(zone));
    }
}
