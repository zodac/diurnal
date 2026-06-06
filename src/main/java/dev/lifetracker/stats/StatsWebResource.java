package dev.lifetracker.stats;

import dev.lifetracker.action.Action;
import dev.lifetracker.user.User;
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

@Path("/stats")
@RolesAllowed("user")
public class StatsWebResource {

    private static final int PAGE_SIZE = 10;

    @Inject @Location("stats") Template statsTemplate;
    @Inject @Location("partials/stats-cards") Template statsCardsTemplate;
    @Inject SecurityIdentity identity;
    @Inject StatsService statsService;

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance statsPage(@QueryParam("page") @DefaultValue("1") int pageNum) {
        User user = currentUser();
        return statsTemplate.data(
                "email", user.email,
                "displayName", user.displayName,
                "darkMode", user.darkMode,
                "hasActions", !Action.findActiveByUser(user.id).isEmpty(),
                "page", getStatsPage(user.id, pageNum));
    }

    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance statsList(@QueryParam("page") @DefaultValue("1") int pageNum) {
        User user = currentUser();
        return statsCardsTemplate.data("page", getStatsPage(user.id, pageNum));
    }

    // Only actions with at least one logged entry are returned by forAllActiveActions;
    // pagination slices that filtered list into pages of PAGE_SIZE.
    private record PaginatedStats(List<ActionStats> items, int totalCount, int totalPages, int currentPage) {}

    private PaginatedStats getStatsPage(UUID userId, int pageNum) {
        List<ActionStats> all = statsService.forAllActiveActions(userId);

        int totalCount = all.size();
        int totalPages = (totalCount + PAGE_SIZE - 1) / PAGE_SIZE;
        int actualPage = Math.max(1, Math.min(pageNum, totalPages == 0 ? 1 : totalPages));
        int skip = (actualPage - 1) * PAGE_SIZE;

        List<ActionStats> items = all.stream()
                .skip(skip)
                .limit(PAGE_SIZE)
                .toList();

        return new PaginatedStats(items, totalCount, totalPages, actualPage);
    }

    private User currentUser() {
        return User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
    }
}
