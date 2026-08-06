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

package net.zodac.diurnal.note;

import io.quarkus.vertx.http.Compressed;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import net.zodac.diurnal.http.EntityTags;
import net.zodac.diurnal.log.DateRanges;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.web.RollbackOnErrorStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The web UI's internal endpoints for notes: the dashboard's date-range feed, plus the save and clear the note box posts to. It is web-UI plumbing,
 * not part of the public API — that is {@link NotesApiResource}, which shares the same {@link NoteService} so the write rules cannot diverge.
 *
 * <p>
 * The feed answers a map of {@code date -> content} covering only the days that HAVE a note, which the dashboard merges into the same per-month cache
 * that holds the calendar's logged-action data. That is what paints the green day numbers and what lets the note box repaint instantly when the
 * selected day changes, so the note panel needs no per-day request of its own. It is {@link Compressed} and carries a weak ETag for the same reasons
 * as the calendar's other feeds: it is on the dashboard's hot loading path, a range of prose gzips heavily, and an unchanged range should cost a
 * {@code 304} rather than a re-send. The {@code Cache-Control} here is supplied by the {@code html-pages} filter ({@code no-cache}); only the ETag is
 * added.
 *
 * <p>
 * The writes answer JSON rather than an HTML fragment, because the note card is rendered once with the page and never swapped — the browser only
 * needs the stored content back to update its cache and the textarea. A rejection is a {@code 422} here where the API answers {@code 400}: the same
 * per-surface split every other text input in the app uses.
 */
@Path("/internal/notes")
@RolesAllowed(Role.Values.USER)
@Produces(MediaType.APPLICATION_JSON)
@RollbackOnErrorStatus
public class NotesInternalResource {

    private static final Logger LOGGER = LogManager.getLogger(NotesInternalResource.class);

    // Not on Response.Status in this JAX-RS version, and the same literal the settings endpoints already answer text rejections with.
    private static final int UNPROCESSABLE_ENTITY = 422;

    private final CurrentUser currentUser;
    private final NoteService noteService;

    /**
     * Injects the current-user accessor and the shared note service.
     *
     * @param currentUser the current-user accessor
     * @param noteService the shared note-mutation service
     */
    @Inject
    public NotesInternalResource(final CurrentUser currentUser, final NoteService noteService) {
        this.currentUser = currentUser;
        this.noteService = noteService;
    }

    /**
     * Returns the user's notes in a date range as a JSON object of {@code yyyy-MM-dd -> content}, holding only the days that have a note.
     *
     * @param start   inclusive start of the range (ISO-8601 date)
     * @param end     inclusive end of the range (ISO-8601 date)
     * @param request the JAX-RS request, used to evaluate the {@code If-None-Match} conditional against the range's ETag
     * @return the range's notes by date, or an empty {@code 304} response
     */
    @Compressed
    @GET
    public Response notes(
        @QueryParam("start") final String start,
        @QueryParam("end") final String end,
        @Context final Request request) {

        final User user = currentUser.get();
        final LocalDate startDate = DateRanges.requireDate("start", start);
        final LocalDate endDate   = DateRanges.requireDate("end", end);

        final EntityTag tag = EntityTags.weak(user.id, startDate, endDate, Note.rangeVersion(user.id, startDate, endDate));
        final Response.ResponseBuilder notModified = request.evaluatePreconditions(tag);
        if (notModified != null) {
            return EntityTags.withValidator(notModified, tag).build();
        }

        // One key opens the whole range, so it is resolved once rather than per note; the map keeps the query's date
        // ordering, so the response reads chronologically even though the client indexes it by key.
        final Map<String, String> byDate = new LinkedHashMap<>();
        noteService.readContents(user.id, Note.findByUserAndRange(user.id, startDate, endDate))
            .forEach((date, content) -> byDate.put(date.toString(), content));
        // The COUNT only, never a note's content - see NoteService's logging rule.
        LOGGER.debug("Notes feed served {} note(s) in [{}, {}] for user {}", byDate.size(), startDate, endDate, user.email);
        return EntityTags.withValidator(Response.ok(byDate), tag).build();
    }

    /**
     * Writes the day's note from the note box, returning the stored content so the browser can update its cache and textarea.
     *
     * <p>
     * The body is JSON rather than the form encoding every other internal mutation uses, because a note is far too big for a form attribute:
     * Quarkus caps one at {@code quarkus.http.limits.max-form-attribute-size} (2&nbsp;KB by default) and answers {@code 413} above it, before the
     * request ever reaches this method. A note runs to {@link net.zodac.diurnal.text.TextFields#NOTE_MAX_LENGTH} code points, which URL-encodes to
     * well over 100&nbsp;KB of non-ASCII text — so raising that limit far enough for this ONE field would also let every other form in the app (the
     * login email, an action name) carry a body that size. Nothing is lost by the change: the note card is driven by a plain {@code fetch}, not by
     * an HTMX form post, so it can send whatever shape it likes, and JSON is what the public twin already takes.
     *
     * @param date    the day to write against
     * @param request the submitted content ({@code null} or blank removes the note)
     * @return the stored note, or {@code 422} with the rejection message
     */
    @POST
    @Path("/{date}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response save(
        @PathParam("date") final LocalDate date,
        final @Nullable NoteSubmission request) {

        return translate(noteService.save(currentUser.get(), date, request == null ? null : request.content()));
    }

    /**
     * Removes the day's note. Clearing a day with no note is a no-op success.
     *
     * @param date the day to clear
     * @return the now-empty note
     */
    @POST
    @Path("/{date}/delete")
    @Transactional
    public Response clear(@PathParam("date") final LocalDate date) {
        final User user = currentUser.get();
        LOGGER.debug("Note clear requested for {} by user {}", date, user.email);
        return translate(noteService.clear(user, date));
    }

    private static Response translate(final NoteResult result) {
        return switch (result) {
            case final NoteResult.Saved saved -> Response.ok(new SavedNote(saved.date().toString(), saved.content())).build();
            case final NoteResult.Cleared cleared -> Response.ok(new SavedNote(cleared.date().toString(), "")).build();
            // 422 on the web where the API answers 400 - the same per-surface split every other text input uses.
            case final NoteResult.Invalid invalid -> Response.status(UNPROCESSABLE_ENTITY)
                .entity(new ApiErrorResponse(invalid.message()))
                .build();
        };
    }

    /**
     * The note box's submission. Public because Quarkus generates the Jackson deserialiser as a separate class, which cannot reach a private nested
     * record's canonical constructor ({@code IllegalAccessError} at request time, surfacing as a {@code 500}).
     *
     * @param content the submitted content; blank or {@code null} removes the day's note
     */
    public record NoteSubmission(@Nullable String content) {

    }

    /**
     * The stored note echoed back to the note box, so the browser can refresh its cache and textarea from what was actually persisted rather than
     * from what it sent. Public for the same serialisation reason as {@link NoteSubmission}.
     *
     * @param date    the day, as an ISO-8601 date string
     * @param content the stored content, empty when the day's note was removed
     */
    public record SavedNote(String date, String content) {

    }
}
