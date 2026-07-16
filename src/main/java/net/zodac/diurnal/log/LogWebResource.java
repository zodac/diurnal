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
import io.quarkus.vertx.http.Compressed;
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
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;

/**
 * Increment/decrement endpoints for a day's action counts, plus the dashboard day-panel partials.
 */
@Path("/internal/logs")
@RolesAllowed(Role.Values.USER)
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

    @Inject
    CurrentUser currentUser;

    @Inject
    AppClock clock;

    @Inject
    LogService logService;

    // ── Day panel ──────────────────────────────────────────────────────────

    /**
     * Renders the dashboard day panel for a date (or a "future" placeholder for tomorrow onward).
     */
    @GET
    @Path("/day/{date}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dayPanel(@PathParam("date") final LocalDate date) {
        final User user = currentUser.get();
        final boolean future = LogGuards.isFuture(date, user, clock);
        final var page = future ? null : getActions(user.id, date, 1, "", user.pageSize);

        return dayPanelTemplate
            .data("date", date)
            .data("dateLabel", date.format(DAY_LABEL))
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
        final User user = currentUser.get();
        final var page = getActions(user.id, date, pageNum, searchTerm, user.pageSize);
        return dayActionsListTemplate.data("date", date, "page", page);
    }

    /**
     * Renders every day of a month in ONE response: a JSON map of ISO date → day-panel HTML.
     *
     * <p>
     * The dashboard loads the selected day on its own, then calls this once to back-fill the rest of the month into its client-side cache, so
     * flicking between days is instant. Doing it as a single request with one range query avoids a per-day fan-out by fetching the action list and
     * the whole month's counts once and paging each day from memory.
     *
     * <p>
     * The response is the app's largest payload by far (~30 rendered panels of repetitive HTML in one JSON body), so it is explicitly
     * {@link Compressed} — a targeted exception to the deliberately narrow global {@code quarkus.http.compress-media-types} (see the BREACH note in
     * {@code application.properties}). Compressing it is safe from a BREACH standpoint: the body carries no secret (no CSRF token — CSRF protection
     * is origin-based — and the session token never appears in a response body), and the only request-controlled input, the {@code month} path
     * segment, must parse as {@code yyyy-MM} before anything is rendered.
     *
     * @param month the month to render, as {@code yyyy-MM}
     * @return {@code 200} with a JSON object mapping each {@code yyyy-MM-dd} to its day-panel HTML, or {@code 400} when {@code month} is not a valid
     *     {@code yyyy-MM}
     */
    @Compressed
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

        final User user = currentUser.get();
        final LocalDate start = yearMonth.atDay(1);
        final LocalDate end = yearMonth.atEndOfMonth();

        // Fetch the action list and the month's logs ONCE, then page each day from memory.
        final List<Action> all = Action.findByUser(user.id);
        final Map<LocalDate, Map<UUID, Integer>> countsByDate = ActionLog.findByUserAndRange(user.id, start, end)
            .stream()
            .collect(Collectors.groupingBy(log -> log.logDate, Collectors.toMap(log -> log.actionId, log -> log.count)));

        final Map<String, String> panels = new LinkedHashMap<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            final boolean future = LogGuards.isFuture(date, user, clock);
            final var page = future ? null : paginate(all, countsByDate.getOrDefault(date, Map.of()), 1, "", user.pageSize);
            panels.put(date.toString(), dayPanelTemplate
                .data("date", date)
                .data("dateLabel", date.format(DAY_LABEL))
                .data("future", future)
                .data("page", page)
                .render());
        }
        return Response.ok(panels).build();
    }

    // fillerRows: blank rows that keep every paginated page the height of a full page.
    // Only populated when there is more than one page; a single short page keeps its natural height.
    private record PaginatedDayActions(List<DayActionStatus> items, int totalCount, int totalPages, int currentPage, List<Integer> fillerRows) {

    }

    private PaginatedDayActions getActions(final UUID userId, final LocalDate date, final int pageNum, final String searchTerm, final int pageSize) {
        return paginate(Action.findByUser(userId), ActionLog.countsByAction(userId, date), pageNum, searchTerm, pageSize);
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
     * Returns the day-action-item partial for a single action on a given date. Used by the confirm-delete Cancel button to restore the normal view.
     */
    @GET
    @Path("/{date}/{actionId}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response dayActionItem(
        @PathParam("date") final LocalDate date,
        @PathParam("actionId") final UUID actionId) {

        final User user = currentUser.get();
        final Action action = LogGuards.ownedAction(user, actionId);
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

        final User user = currentUser.get();
        final Action action = LogGuards.ownedAction(user, actionId);
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
        return translate(date, logService.deleteEntry(currentUser.get(), date, actionId));
    }

    // ── Increment ─────────────────────────────────────────────────────────

    /**
     * Increments (or creates) the day's count for an action by {@code amount} (default 1), capped at {@code MAX_DAILY_COUNT}. Non-positive amounts
     * leave the count unchanged.
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
        return adjust(date, actionId, amount, true);
    }

    // ── Decrement ─────────────────────────────────────────────────────────

    /**
     * Decrements the day's count for an action by {@code amount} (default 1), deleting the entry when it reaches zero. Non-positive amounts leave the
     * count unchanged.
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
        return adjust(date, actionId, amount, false);
    }

    // ── Set count ─────────────────────────────────────────────────────────

    /**
     * Sets the day's count for an action to an explicit value, deleting the entry when zero or below. Values above {@code MAX_DAILY_COUNT} are
     * silently clamped.
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
        // The web form's input contract: a negative count is coerced to zero, i.e. "clear the day"
        // (the API instead rejects it with a 400) — a per-surface translation of intent, not a
        // different write rule; the shared LogService owns the cap and delete-at-zero semantics.
        return translate(date, logService.updateCount(currentUser.get(), date, actionId, Math.max(requestedCount, 0)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Response adjust(final LocalDate date, final UUID actionId, final int amount, final boolean increment) {
        // The web form's input contract: a non-positive amount is a no-op that just re-renders the
        // current count (the API instead rejects it with a 400) — a per-surface translation of intent,
        // not a different write rule; the shared LogService owns the atomic adjust semantics.
        final User user = currentUser.get();
        final int delta = Math.max(amount, 0);
        final LogResult result = delta == 0
            ? logService.readCount(user, date, actionId)
            : logService.adjust(user, date, actionId, delta, increment);
        return translate(date, result);
    }

    private Response translate(final LocalDate date, final LogResult result) {
        return switch (result) {
            case LogResult.FutureDate ignored -> Response.status(Response.Status.BAD_REQUEST).build();
            case LogResult.NotOwned ignored -> Response.status(Response.Status.NOT_FOUND).build();
            case LogResult.Updated updated -> Response.ok(item(date, updated.action(), updated.count())).build();
        };
    }

    private TemplateInstance item(final LocalDate date, final Action action, final int count) {
        return dayActionItemTemplate.data("date", date, "action", action, "count", count);
    }

    /**
     * An action paired with its count for a given day (0 when not yet logged).
     */
    public record DayActionStatus(Action action, int count) {

    }
}
