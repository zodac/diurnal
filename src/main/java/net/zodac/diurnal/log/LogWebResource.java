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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH);

    @Inject
    @Location("partials/day-panel")
    Template dayPanelTemplate;
    @Inject
    @Location("partials/day-actions-list")
    Template dayActionsListTemplate;
    @Inject
    @Location("partials/day-action-item")
    Template dayActionItemTemplate;
    @Inject
    @Location("partials/day-action-item-confirm-delete")
    Template dayActionItemConfirmDeleteTemplate;

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

    /**
     * Renders every day of a month in ONE response: a JSON map of ISO date → day-panel HTML.
     *
     * <p>The dashboard loads the selected day on its own, then calls this once to back-fill the rest of
     * the month into its client-side cache, so flicking between days is instant. Doing it as a single
     * request with one range query avoids a per-day fan-out — a burst of ~30 concurrent requests that
     * could exhaust the small JDBC pool — by fetching the action list and the whole month's counts once
     * and paging each day from memory.
     *
     * @param month the month to render, as {@code yyyy-MM}
     * @return {@code 200} with a JSON object mapping each {@code yyyy-MM-dd} to its day-panel HTML, or
     *         {@code 400} when {@code month} is not a valid {@code yyyy-MM}
     */
    @GET
    @Path("/month/{month}")
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response monthPanels(@PathParam("month") final String month) {
        final YearMonth yearMonth;
        try {
            yearMonth = YearMonth.parse(month);
        } catch (final DateTimeParseException e) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        final User user = currentUser();
        final LocalDate start = yearMonth.atDay(1);
        final LocalDate end = yearMonth.atEndOfMonth();

        // Fetch the action list and the month's logs ONCE, then page each day from memory.
        final List<Action> all = Action.findActiveByUser(user.id);
        final Map<LocalDate, Map<UUID, Integer>> countsByDate = ActionLog.findByUserAndRange(user.id, start, end)
                .stream()
                .collect(Collectors.groupingBy(
                        log -> log.logDate,
                        Collectors.toMap(log -> log.actionId, log -> log.count)));

        final Map<String, String> panels = new LinkedHashMap<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            final boolean future = isFuture(date, user);
            final var page = future ? null : paginate(all, countsByDate.getOrDefault(date, Map.of()), 1, "", user.pageSize);
            panels.put(date.toString(), dayPanelTemplate
                    .data("date", date)
                    .data("dateLabel", date.format(DAY_LABEL))
                    .data("theme", user.theme)
                    .data("future", future)
                    .data("page", page)
                    .render());
        }
        return Response.ok(panels).build();
    }

    // fillerRows: blank rows that keep every paginated page the height of a full page.
    // Only populated when there is more than one page; a single short page keeps its natural height.
    private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages,
                                       int currentPage, List<Integer> fillerRows) {
    }

    private PaginatedDayActions getActions(final UUID userId, final LocalDate date, final int pageNum, final String searchTerm, final int pageSize) {
        return paginate(Action.findActiveByUser(userId), ActionLog.countsByAction(userId, date), pageNum, searchTerm, pageSize);
    }

    // Pages a day's actions purely in memory, given a pre-fetched action list and that day's counts.
    // Shared by the single-day fetch (which queries both per call) and the whole-month back-fill (which
    // queries the list and the month's counts ONCE, then pages every day from these without more queries).
    private static PaginatedDayActions paginate(final List<Action> all, final Map<UUID, Integer> counts,
                                                final int pageNum, final String searchTerm, final int pageSize) {
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

    // ── Single item ───────────────────────────────────────────────────────

    /**
     * Returns the day-action-item partial for a single action on a given date.
     * Used by the confirm-delete Cancel button to restore the normal view.
     */
    @GET
    @Path("/{date}/{actionId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response dayActionItem(
            @PathParam("date") final LocalDate date,
            @PathParam("actionId") final UUID actionId) {

        final User user = currentUser();
        final Action action = ownedAction(user, actionId);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        final ActionLog entry = ActionLog.findEntry(user.id, actionId, date);
        return Response.ok(item(date, action, entry == null ? 0 : entry.count)).build();
    }

    /**
     * Returns the in-place confirm-delete div for a day's action log entry.
     */
    @GET
    @Path("/{date}/{actionId}/confirm-delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response confirmDeleteEntry(
            @PathParam("date") final LocalDate date,
            @PathParam("actionId") final UUID actionId) {

        final User user = currentUser();
        final Action action = ownedAction(user, actionId);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        return Response.ok(dayActionItemConfirmDeleteTemplate.data("date", date, "action", action)).build();
    }

    /**
     * Deletes the day's log entry for an action, returning the item at count zero.
     */
    @POST
    @Path("/{date}/{actionId}/delete")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response deleteEntry(
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
        if (entry != null) {
            entry.delete();
        }

        return Response.ok(item(date, action, 0)).build();
    }

    // ── Increment ─────────────────────────────────────────────────────────

    /**
     * Increments (or creates) the day's count for an action by {@code amount} (default 1),
     * capped at {@code MAX_DAILY_COUNT}. Non-positive amounts leave the count unchanged.
     */
    @POST
    @Path("/{date}/{actionId}/increment")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response increment(
            @PathParam("date") final LocalDate date,
            @PathParam("actionId") final UUID actionId,
            @DefaultValue("1") @FormParam("amount") final int amount) {

        final User user = currentUser();
        if (isFuture(date, user)) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        final Action action = ownedAction(user, actionId);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        final int delta = Math.max(amount, 0);

        ActionLog entry = ActionLog.findEntry(user.id, actionId, date);
        if (entry == null) {
            if (delta <= 0) {
                return Response.ok(item(date, action, 0)).build();
            }
            entry = new ActionLog();
            entry.userId   = user.id;
            entry.actionId = actionId;
            entry.logDate  = date;
            entry.count    = Math.min(delta, ActionLog.MAX_DAILY_COUNT);
            entry.persist();
            return Response.ok(item(date, action, entry.count)).build();
        }

        entry.count = Math.min(entry.count + delta, ActionLog.MAX_DAILY_COUNT);
        entry.persist();
        return Response.ok(item(date, action, entry.count)).build();
    }

    // ── Decrement ─────────────────────────────────────────────────────────

    /**
     * Decrements the day's count for an action by {@code amount} (default 1), deleting the
     * entry when it reaches zero. Non-positive amounts leave the count unchanged.
     */
    @POST
    @Path("/{date}/{actionId}/decrement")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response decrement(
            @PathParam("date") final LocalDate date,
            @PathParam("actionId") final UUID actionId,
            @DefaultValue("1") @FormParam("amount") final int amount) {

        final User user = currentUser();
        if (isFuture(date, user)) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        final Action action = ownedAction(user, actionId);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        final int delta = Math.max(amount, 0);

        final ActionLog entry = ActionLog.findEntry(user.id, actionId, date);
        if (entry == null) {
            return Response.ok(item(date, action, 0)).build();
        }

        final int newCount = entry.count - delta;
        if (newCount <= 0) {
            entry.delete();
            return Response.ok(item(date, action, 0)).build();
        }

        entry.count = newCount;
        entry.persist();
        return Response.ok(item(date, action, newCount)).build();
    }

    // ── Set count ─────────────────────────────────────────────────────────

    /**
     * Sets the day's count for an action to an explicit value, deleting the entry when zero or below.
     * Values above {@code MAX_DAILY_COUNT} are silently clamped.
     */
    @POST
    @Path("/{date}/{actionId}/set")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response updateCount(
            @PathParam("date") final LocalDate date,
            @PathParam("actionId") final UUID actionId,
            @DefaultValue("0") @FormParam("count") final int requestedCount) {

        final User user = currentUser();
        if (isFuture(date, user)) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }

        final Action action = ownedAction(user, actionId);
        if (action == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        final int newCount = Math.clamp(requestedCount, 0, ActionLog.MAX_DAILY_COUNT);

        ActionLog entry = ActionLog.findEntry(user.id, actionId, date);
        if (newCount <= 0) {
            if (entry != null) {
                entry.delete();
            }
            return Response.ok(item(date, action, 0)).build();
        }

        if (entry == null) {
            entry = new ActionLog();
            entry.userId   = user.id;
            entry.actionId = actionId;
            entry.logDate  = date;
            entry.count    = newCount;
            entry.persist();
        } else {
            entry.count = newCount;
            entry.persist();
        }

        return Response.ok(item(date, action, newCount)).build();
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
