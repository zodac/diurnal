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
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * Serves the full actions page. The page's HTMX list/row partials and mutations live under {@code /internal/actions}
 * ({@link ActionsInternalResource}).
 */
@Path("/actions")
@RolesAllowed(Role.Values.USER)
public class ActionsWebResource {

    @Inject
    @Location("actions")
    Template actionsTemplate;

    @Inject CurrentUser currentUser;

    /**
     * Renders the full actions page for the current user.
     *
     * @return the rendered page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance actionsPage() {
        final User user = currentUser.get();
        final var page = ActionsInternalResource.getActions(user.id, 1, "", user.pageSize);
        return actionsTemplate
                .data("displayName", user.displayName)
                .data("email", user.email)
                .data("isAdmin", user.isAdmin())
                .data("page", page)
                .data("theme", user.theme)
                .data("font", user.font);
    }
}
