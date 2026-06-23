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

package net.zodac.diurnal.log;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;

/**
 * Increment/decrement endpoints for a day's action counts, plus the dashboard day-panel partials.
 */
@Path("/logs")
@RolesAllowed("user")
public class LogWebResource {

    private static final DateTimeFormatter DAY_LABEL =
            DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH);

    @Inject
    @Location("partials/day-panel")
    Template dayPanelTemplate;
    @Inject
    @Location("partials/day-actions-list")
    Template dayActionsListTemplate;
    @Inject
    @Location("partials/day-action-item")
    Template dayActionItemTemplate;

    @Inject SecurityIdentity identity;
    @Inject AppClock clock;

    // ── Day panel ──────────────────────────────────────────────────────────

    /**
     * Renders the dashboard day panel for a date (or a "future" placeholder for tomorrow onward).
     */
    @GET
    @Path("/day/{date}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dayPanel(@PathParam("date") final LocalDate date) {
        final User user = currentUser();
        final boolean future = isFuture(date, user);
        final var page = future ? null : getActions(user.id, date, 1, "", user.pageSize);

        return dayPanelTemplate
            .data("date", date)
            .data("dateLabel", date.format(DAY_LABEL))
            .data("theme", user.theme)
            .data("future", future)
            .data("page", page);
    }

    /**
     * Returns the paginated day-actions list partial for HTMX (search + pagination).
     */
    @GET
    @Path("/day/{date}/list")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dayList(
            @PathParam("date") final LocalDate date,
            @QueryParam("page") @DefaultValue("1") final int pageNum,
            @QueryParam("q") @DefaultValue("") final String searchTerm) {
        final User user = currentUser();
        final var page = getActions(user.id, date, pageNum, searchTerm, user.pageSize);
        return dayActionsListTemplate.data("date", date, "page", page);
    }

    // fillerRows: blank rows that keep every paginated page the height of a full page.
    // Only populated when there is more than one page; a single short page keeps its natural height.
    private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages,
                                       int currentPage, List<Integer> fillerRows) {
    }

    private PaginatedDayActions getActions(final UUID userId, final LocalDate date, final int pageNum, final String searchTerm, final int pageSize) {
        final List<Action> all = Action.findActiveByUser(userId);
        final Map<UUID, Integer> counts = ActionLog.countsByAction(userId, date);

        final var filtered = all.stream()
                .filter(a -> searchTerm == null || searchTerm.isBlank()
                        || a.name.toLowerCase(Locale.ROOT).contains(searchTerm.toLowerCase(Locale.ROOT)))
                .map(a -> new DayActionStatus(a, counts.getOrDefault(a.id, 0)))
                // Highest count first; equal counts (including 0) keep the DB's alphabetical
                // order, since `all` arrives sorted by name and sorted() is stable.
                .sorted(Comparator.comparingInt(DayActionStatus::count).reversed())
                .toList();

        final int totalCount = filtered.size();
        final int totalPages = (totalCount + pageSize - 1) / pageSize;
        final int actualPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);
        final int skip = (actualPage - 1) * pageSize;

        final var items = filtered.stream()
                .skip(skip)
                .limit(pageSize)
                .toList();

        final int fillers = totalPages > 1 ? Math.max(0, pageSize - items.size()) : 0;
        final List<Integer> fillerRows = IntStream.range(0, fillers).boxed().toList();

        return new PaginatedDayActions(items, totalCount, totalPages, actualPage, fillerRows);
    }

    // ── Increment ─────────────────────────────────────────────────────────

    /**
     * Increments (or creates) the day's count for an action, capped at {@code MAX_DAILY_COUNT}.
     */
    @POST
    @Path("/{date}/{actionId}/increment")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response increment(
            @PathParam("date") final LocalDate date,
            @PathParam("actionId") final UUID actionId) {

        final User user = currentUser();
        if (isFuture(date, user)) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        final Action action = ownedAction(user, actionId);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        ActionLog entry = ActionLog.findEntry(user.id, actionId, date);
        if (entry == null) {
            entry = new ActionLog();
            entry.userId   = user.id;
            entry.actionId = actionId;
            entry.logDate  = date;
            entry.count    = 1;
            entry.persist();
        } else if (entry.count < ActionLog.MAX_DAILY_COUNT) {
            entry.count++;
            entry.persist();
        }

        return Response.ok(item(date, action, entry.count)).build();
    }

    // ── Decrement ─────────────────────────────────────────────────────────

    /**
     * Decrements the day's count for an action, deleting the entry when it reaches zero.
     */
    @POST
    @Path("/{date}/{actionId}/decrement")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response decrement(
            @PathParam("date") final LocalDate date,
            @PathParam("actionId") final UUID actionId) {

        final User user = currentUser();
        if (isFuture(date, user)) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        final Action action = ownedAction(user, actionId);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        final ActionLog entry = ActionLog.findEntry(user.id, actionId, date);
        if (entry == null) {
            return Response.ok(item(date, action, 0)).build();
        }

        if (entry.count <= 1) {
            entry.delete();
            return Response.ok(item(date, action, 0)).build();
        }

        entry.count--;
        entry.persist();
        return Response.ok(item(date, action, entry.count)).build();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private TemplateInstance item(final LocalDate date, final Action action, final int count) {
        return dayActionItemTemplate.data("date", date, "action", action, "count", count);
    }

    // Actions can only be logged for today or earlier, in the user's configured timezone
    // (falling back to the server default when the user hasn't chosen one).
    private boolean isFuture(final LocalDate date, final User user) {
        return date.isAfter(clock.today(clock.zoneFor(user.timezone)));
    }

    private User currentUser() {
        return User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
    }

    private Action ownedAction(final User user, final UUID actionId) {
        return Action.<Action>find("id = ?1 and userId = ?2 and archived = false", actionId, user.id)
                .firstResult();
    }

    /**
     * An action paired with its count for a given day (0 when not yet logged).
     */
    public record DayActionStatus(Action action, int count) {
    }
}
