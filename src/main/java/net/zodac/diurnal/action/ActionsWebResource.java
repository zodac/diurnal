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

package net.zodac.diurnal.action;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.i18n.MessageBundles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Locale;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizes;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * Serves the full actions page. The page's HTMX list/row partials and mutations live under {@code /internal/actions}
 * ({@link ActionsInternalResource}).
 */
@Path("/actions")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
public class ActionsWebResource {

    private final Template actionsTemplate;
    private final CurrentUser currentUser;
    private final ActionService actionService;

    /**
     * Injects the page template, current-user accessor and the shared action service.
     *
     * @param actionsTemplate the full actions-page template
     * @param currentUser the current-user accessor
     * @param actionService the shared action service, for the new-action form's pre-filled colour
     */
    @Inject
    ActionsWebResource(@Location("actions") final Template actionsTemplate, final CurrentUser currentUser,
        final ActionService actionService) {
        this.actionsTemplate = actionsTemplate;
        this.currentUser = currentUser;
        this.actionService = actionService;
    }

    /**
     * Renders the full actions page for the current user.
     *
     * <p>
     * The new-action form's colour picker arrives already set to a suggestion rather than the neutral slate, so an action added without touching the
     * picker is still tellable apart from every other one on the calendar. It is the same suggestion the randomise button would fetch, taken here at
     * render time; the page's script re-draws it after each successful add (the colour just used is in use from then on).
     *
     * @return the rendered page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance actionsPage() {
        final User user = currentUser.get();
        final var page = ActionsInternalResource.getActions(user.id, 1, "", PageSizes.forSection(user, PageSection.ACTIONS),
            Locale.forLanguageTag(user.language));
        return actionsTemplate
                .data("displayName", user.displayName)
                .data("email", user.email)
                .data("isAdmin", user.isAdmin())
                .data("page", page)
                .data("suggestedColour", actionService.suggestColour(user))
                .data("theme", user.theme)
                .data("font", user.font)
                .data("language", user.language)
                .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, Locale.forLanguageTag(user.language));
    }
}
