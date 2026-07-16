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
import java.util.List;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * The web UI's internal HTMX endpoint for the Stats page: the paginated stats-cards list partial. The full page stays at {@code GET /stats}
 * ({@link StatsWebResource}); nothing here is part of the public API (that is {@code /api/v1/*}).
 */
@Path("/internal/stats")
@RolesAllowed(Role.Values.USER)
public class StatsInternalResource {

    @Inject
    @Location("partials/stats-cards")
    Template statsCardsTemplate;

    @Inject CurrentUser currentUser;

    @Inject StatsService statsService;

    /**
     * Returns just the stats-cards list partial for HTMX pagination.
     *
     * @param pageNum the 1-based page to render
     * @return the rendered list partial
     */
    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance statsList(@QueryParam("page") @DefaultValue("1") final int pageNum) {
        final User user = currentUser.get();
        return statsCardsTemplate
                .data("decimalPlaces", user.decimalPlaces)
                .data("statsFields", ActionStatField.displayFields(user.statsFields))
                .data("page", paginate(statsService.forAllActiveActions(user.id), pageNum, user.pageSize));
    }

    /**
     * Pages a pre-computed list of per-action stats in memory. Shared with the full-page render ({@link StatsWebResource}). Only actions with at
     * least one logged entry are in the list ({@code forAllActiveActions} filters them).
     *
     * @param all      every active action's stats, in display order
     * @param pageNum  the requested 1-based page (clamped into range)
     * @param pageSize the user's page size
     * @return the requested page of stats
     */
    static PaginatedStats paginate(final List<ActionStats> all, final int pageNum, final int pageSize) {
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

    /**
     * One page of per-action stats, as rendered by the list partial and the full page.
     *
     * @param items       the page's stats
     * @param totalCount  the total number of active actions
     * @param totalPages  the page count
     * @param currentPage the rendered (clamped) 1-based page
     */
    record PaginatedStats(List<ActionStats> items, int totalCount, int totalPages, int currentPage) {

    }
}
