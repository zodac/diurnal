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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
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
import java.util.List;
import net.zodac.diurnal.http.EntityTags;
import net.zodac.diurnal.log.DateRanges;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.web.RollbackOnErrorStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.ParameterIn;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The public REST API for a user's per-day notes: {@code GET /api/v1/notes} lists the notes in a date range, {@code GET /api/v1/notes/{date}} reads
 * one day's note, and {@code PUT}/{@code DELETE} write and remove it. External integrations call these with a Bearer session token (see
 * {@code POST /api/v1/auth/login}); the dashboard reads the same data through {@link NotesInternalResource}. The write endpoints share one
 * implementation with the web UI — both surfaces call the same {@link NoteService}, so the write rules (the shared text pipeline's length and content
 * checks, a blank note removing the day's row, a future date being allowed) cannot diverge; this resource only translates {@link NoteResult}
 * outcomes into JSON.
 *
 * <p>
 * The range feed is {@link Compressed}: the dashboard warms three months of note content in one request, which is repetitive prose that gzips
 * heavily. This is a targeted exception to the deliberately narrow global {@code quarkus.http.compress-media-types} (see the BREACH note in
 * {@code application.properties}), safe for the same reason as the logged-events feed: the body carries no secret (no CSRF token — protection is
 * origin-based — and the session token never appears in a body), and the only request-controlled inputs, {@code start}/{@code end}, must parse as
 * ISO-8601 dates before anything is returned.
 */
@Tag(name = "Notes", description = "Read and write a user's per-day free-text notes.")
@Path("/api/v1/notes")
@RolesAllowed(Role.Values.USER)
@Produces(MediaType.APPLICATION_JSON)
@RollbackOnErrorStatus
public class NotesApiResource {

    private static final Logger LOGGER = LogManager.getLogger(NotesApiResource.class);

    /**
     * How many notes one page of the range listing holds.
     *
     * <p>
     * A FIXED size, deliberately not the user's "items per page" preference that every other list endpoint uses. Those list things a user scrolls
     * through in the UI, so matching their preference makes the API and the page agree. Nothing in the UI reads this endpoint (the dashboard has its
     * own internal feed), so there is no page to agree with — and the reason to bound it is not readability but SIZE: a note runs to
     * {@link net.zodac.diurnal.text.TextFields#NOTE_MAX_LENGTH} characters, so an unbounded range of them is the largest response the API can
     * produce. Tying that ceiling to a preference a user can set to 5 would make the bound meaningless.
     *
     * <p>
     * Thirty-one is one full calendar month: the natural unit for a per-day resource, so the common "give me this month" call is exactly one page,
     * and a year is twelve predictable requests rather than an arbitrary number.
     */
    private static final int PAGE_SIZE = 31;

    private final CurrentUser currentUser;
    private final NoteService noteService;

    /**
     * Injects the current-user accessor and the shared note service.
     *
     * @param currentUser the current-user accessor
     * @param noteService the shared note-mutation service
     */
    @Inject
    public NotesApiResource(final CurrentUser currentUser, final NoteService noteService) {
        this.currentUser = currentUser;
        this.noteService = noteService;
    }

    /**
     * Returns the user's notes within a date range, earliest first, or {@code 304} when the range is unchanged since the caller's ETag.
     *
     * @param start   inclusive start of the range (ISO-8601 date)
     * @param end     inclusive end of the range (ISO-8601 date)
     * @param request the JAX-RS request, used to evaluate the {@code If-None-Match} conditional against the range's ETag
     * @return the notes in the range, or an empty {@code 304} response
     */
    @Compressed
    @GET
    @Operation(
        summary = "List notes in a date range",
        description = "Returns one page of the user's notes within the date range, earliest first. Days with no note are simply absent, so the "
        + "result is exactly the set of days that have one. Paginated at a fixed 31 notes per page - one full calendar month, so the usual "
        + "'give me this month' call is a single page - because a note may run to 10,000 characters and an unbounded range would be the largest "
        + "response the API can produce. An out-of-range page is rejected with a 400 (never silently clamped)."
    )
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "One page of the notes in the range, earliest first.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NotesPageDto.class))),
        @APIResponse(responseCode = "304", description = "Not modified: the range is unchanged since the ETag in the 'If-None-Match' request "
                + "header, so no body is returned."),
        @APIResponse(responseCode = "400", description = "The 'start' or 'end' query parameter is missing or not a valid ISO-8601 date, or the "
                + "requested page is out of range.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    })
    public Response notes(
        @Parameter(name = "start", in = ParameterIn.QUERY, required = true,
        description = "Inclusive start of the range, as an ISO-8601 date (yyyy-MM-dd); only the date part is used.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-01"))
        @QueryParam("start") final String start,
        @Parameter(name = "end", in = ParameterIn.QUERY, required = true,
        description = "Inclusive end of the range, as an ISO-8601 date (yyyy-MM-dd); only the date part is used.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-30"))
        @QueryParam("end") final String end,
        @Parameter(name = "page", in = ParameterIn.QUERY,
        description = "The 1-based page of the range to return, 31 notes per page. Defaults to the first page.",
        schema = @Schema(type = SchemaType.INTEGER, examples = "1"))
        @QueryParam("page") @DefaultValue("1") final int pageNum,
        @Context final Request request) {

        final User user = currentUser.get();
        final LocalDate startDate = DateRanges.requireDate("start", start);
        final LocalDate endDate   = DateRanges.requireDate("end", end);

        // The response carries nothing but the notes themselves, so the range's own signature - plus the page being asked for - is the whole
        // validator.
        final EntityTag tag = EntityTags.weak(user.id, startDate, endDate, pageNum, Note.rangeVersion(user.id, startDate, endDate));
        final Response.ResponseBuilder notModified = request.evaluatePreconditions(tag);
        if (notModified != null) {
            return EntityTags.withPrivateValidator(notModified, tag).build();
        }

        // One key opens the whole range, so it is resolved once rather than per note. A note that will not open is
        // dropped rather than reported: one damaged row must not fail the range for every other day in it.
        final List<NoteDto> all = noteService.readContents(user.id, Note.findByUserAndRange(user.id, startDate, endDate))
            .entrySet().stream()
            .map(entry -> new NoteDto(entry.getKey().toString(), entry.getValue()))
            .toList();
        final int totalPages = (all.size() + PAGE_SIZE - 1) / PAGE_SIZE;

        // Surface input policy, matching every other paginated API endpoint: an out-of-range page is REJECTED rather than clamped, so a page number
        // is never silently answered with some other page. Page 1 of an empty range is legal and returns nothing.
        if (pageNum < 1 || pageNum > Math.max(1, totalPages)) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse("Page " + pageNum + " is out of range"))
                .build();
        }

        final List<NoteDto> items = all.stream()
            .skip((long) (pageNum - 1) * PAGE_SIZE)
            .limit(PAGE_SIZE)
            .toList();
        // The COUNT only, never a note's content - see NoteService's logging rule.
        LOGGER.debug("Notes API read {} of {} note(s) in [{}, {}] (page {}) for user {}",
            items.size(), all.size(), startDate, endDate, pageNum, user.email);
        return EntityTags.withPrivateValidator(Response.ok(new NotesPageDto(items, all.size(), totalPages, pageNum)), tag).build();
    }

    /**
     * One page of a note range.
     *
     * @param items       the page's notes, earliest first
     * @param totalCount  the total number of notes in the requested range, across all pages
     * @param totalPages  the page count
     * @param currentPage the returned 1-based page (always the requested page - an out-of-range page is rejected, not clamped)
     */
    @Schema(description = "One page of a note range.")
    public record NotesPageDto(
        @Schema(description = "The page's notes, earliest first.") List<NoteDto> items,
        @Schema(examples = "45", description = "The total number of notes in the requested range, across all pages.") int totalCount,
        @Schema(examples = "2", description = "The total number of pages.") int totalPages,
        @Schema(examples = "1", description = "The returned 1-based page (always the requested page; out-of-range is rejected).") int currentPage) {
    }

    /**
     * Returns one day's note, or {@code 404} when the day has none.
     *
     * @param date    the day to read, as an ISO-8601 date
     * @param request the JAX-RS request, used to evaluate the {@code If-None-Match} conditional against the day's ETag
     * @return the day's note, or an empty {@code 304} response
     */
    @GET
    @Path("/{date}")
    @Operation(
        summary = "Get a day's note",
        description = "Returns the note written against the given day. A day with no note answers 404, which is how a caller distinguishes an empty "
        + "day from one holding an empty note (the latter cannot exist - a blank note is removed).")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The day's note.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NoteDto.class))),
        @APIResponse(responseCode = "304", description = "Not modified: the day is unchanged since the ETag in the 'If-None-Match' request header, "
                + "so no body is returned."),
        @APIResponse(responseCode = "400", description = "The date is not a valid ISO-8601 date."),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "The day has no note.")
    })
    public Response note(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to read, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @Context final Request request) {

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);

        // A single day is just a one-day range, so it reuses the same signature query.
        final EntityTag tag = EntityTags.weak(user.id, day, Note.rangeVersion(user.id, day, day));
        final Response.ResponseBuilder notModified = request.evaluatePreconditions(tag);
        if (notModified != null) {
            return EntityTags.withPrivateValidator(notModified, tag).build();
        }

        final Note found = Note.findEntry(user.id, day);
        LOGGER.debug("Notes API read {} for user {}: {}", day, user.email, found == null ? "absent" : "present");
        if (found == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return noteService.readContent(found)
            .map(content -> EntityTags.withPrivateValidator(Response.ok(NoteDto.of(found, content)), tag).build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * Writes the day's note, creating it or overwriting whatever was there. Blank content removes the note, mirroring a count of zero removing a log
     * entry.
     *
     * @param date    the day to write, as an ISO-8601 date
     * @param request the note content
     * @return the stored note
     */
    @PUT
    @Path("/{date}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(
        summary = "Write a day's note",
        description = "Creates the note or overwrites whatever was there. Blank content removes the day's note and echoes an empty string, mirroring "
        + "a count of 0 removing a log entry. Unlike a logged action, a note MAY be written against a future date. The content is normalised "
        + "before it is stored (line endings unified, blank-line runs condensed, each line trimmed), and the stored form is what is returned.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The stored note; the content is the normalised form, which may differ from what was sent.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NoteDto.class))),
        @APIResponse(responseCode = "400", description = "The date is not a valid ISO-8601 date, or the content is too long or holds invisible or "
                + "text-direction characters.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    })
    public Response writeNote(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to write, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @RequestBody(required = false, description = "The note content; blank or omitted removes the day's note.")
        final @Nullable NoteRequest request) {

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        final String content = request == null ? null : request.content();

        return switch (noteService.save(user, day, content)) {
            case final NoteResult.Saved saved -> Response.ok(new NoteDto(saved.date().toString(), saved.content())).build();
            case final NoteResult.Cleared cleared -> Response.ok(new NoteDto(cleared.date().toString(), "")).build();
            case final NoteResult.Invalid invalid -> Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse(invalid.message()))
                .build();
        };
    }

    /**
     * Removes the day's note. Removing a note that does not exist is a no-op (still {@code 204}).
     *
     * @param date the day to clear, as an ISO-8601 date
     * @return {@code 204}
     */
    @DELETE
    @Path("/{date}")
    @Transactional
    @Operation(
        summary = "Remove a day's note",
        description = "Deletes the day's note (equivalent to writing blank content). A day with no note is a no-op.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "204", description = "The note was removed (or did not exist)."),
        @APIResponse(responseCode = "400", description = "The date is not a valid ISO-8601 date.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    })
    public Response deleteNote(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to clear, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date) {

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        LOGGER.debug("Notes API delete requested for {} by user {}", day, user.email);
        noteService.clear(user, day);
        return Response.noContent().build();
    }

    /**
     * The body for writing a day's note.
     *
     * @param content the note content; blank removes the note
     */
    @Schema(description = "The content to write for the day.")
    public record NoteRequest(
        @Schema(examples = "Ran 5k before work.", description = "The note content; blank or omitted removes the day's note.")
        @Nullable String content) {
    }

    /**
     * One day's note.
     *
     * @param date    the day, as an ISO-8601 date string
     * @param content the note content, in its stored (normalised) form
     */
    @Schema(description = "One day's free-text note.")
    public record NoteDto(
        @Schema(examples = "2026-06-15", description = "The day, as an ISO-8601 date string.") String date,
        @Schema(examples = "Ran 5k before work.", description = "The note content, in its stored form.") String content) {

        /**
         * Maps a stored note to its API representation.
         *
         * @param note the stored note
         * @return the DTO
         */
        static NoteDto of(final Note note, final String content) {
            return new NoteDto(note.noteDate.toString(), content);
        }
    }
}
