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

package net.zodac.diurnal.stats;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * Serves the full, paginated stats page. The page's HTMX list partial lives under {@code /internal/stats} ({@link StatsInternalResource}).
 */
@Path("/stats")
@RolesAllowed(Role.Values.USER)
public class StatsWebResource {

    @Inject
    @Location("stats")
    Template statsTemplate;

    @Inject CurrentUser currentUser;

    @Inject StatsService statsService;

    /**
     * Renders the full stats page for the current user at the requested page.
     *
     * @param pageNum the 1-based page to render
     * @return the rendered page
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance statsPage(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User user = currentUser.get();
        return statsTemplate
                .data("email", user.email)
                .data("displayName", user.displayName)
                .data("theme", user.theme)
                .data("font", user.font)
                .data("isAdmin", user.isAdmin())
                .data("hasActions", !Action.findByUser(user.id).isEmpty())
                .data("decimalPlaces", user.decimalPlaces)
                .data("statsFields", ActionStatField.displayFields(user.statsFields))
                .data("page", StatsInternalResource.paginate(statsService.forAllActiveActions(user.id), pageNum, user.pageSize));
    }
}
