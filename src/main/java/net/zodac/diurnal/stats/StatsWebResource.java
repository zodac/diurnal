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
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.User;

/**
 * Serves the paginated stats page and its HTMX list partial.
 */
@Path("/stats")
@RolesAllowed("user")
public class StatsWebResource {

    @Inject
    @Location("stats")
    Template statsTemplate;
    @Inject
    @Location("partials/stats-cards")
    Template statsCardsTemplate;
    @Inject SecurityIdentity identity;
    @Inject StatsService statsService;

    /**
     * Renders the full stats page for the current user at the requested page.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance statsPage(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User user = currentUser();
        return statsTemplate
                .data("email", user.email)
                .data("displayName", user.displayName)
                .data("theme", user.theme)
                .data("font", user.font)
                .data("isAdmin", user.isAdmin())
                .data("hasActions", !Action.findActiveByUser(user.id).isEmpty())
                .data("page", getStatsPage(user.id, pageNum, user.pageSize));
    }

    /**
     * Returns just the stats-cards list partial for HTMX pagination.
     */
    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance statsList(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User user = currentUser();
        return statsCardsTemplate.data("page", getStatsPage(user.id, pageNum, user.pageSize));
    }

    // Only actions with at least one logged entry are returned by forAllActiveActions;
    // pagination slices that filtered list into pages of PAGE_SIZE.
    private record PaginatedStats(List<ActionStats> items, int totalCount, int totalPages, int currentPage) {
    }

    private PaginatedStats getStatsPage(final UUID userId, final int pageNum, final int pageSize) {
        final List<ActionStats> all = statsService.forAllActiveActions(userId);

        final int totalCount = all.size();
        final int totalPages = (totalCount + pageSize - 1) / pageSize;
        final int actualPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);
        final int skip = (actualPage - 1) * pageSize;

        final List<ActionStats> items = all.stream()
                .skip(skip)
                .limit(pageSize)
                .toList();

        return new PaginatedStats(items, totalCount, totalPages, actualPage);
    }

    private User currentUser() {
        return User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
    }
}
