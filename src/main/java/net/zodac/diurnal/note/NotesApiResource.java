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
import net.zodac.diurnal.http.ChangeSignature;
import net.zodac.diurnal.http.EntityTags;
import net.zodac.diurnal.log.DateRanges;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.page.PageWindow;
import net.zodac.diurnal.page.Pages;
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
 * The public REST API for a user's per-day notes: {@code GET /api/v1/notes} lists (and searches) them, {@code GET /api/v1/notes/{date}} reads
 * one day's note, and {@code PUT}/{@code DELETE} write and remove it. External integrations call these with a Bearer session token (see
 * {@code POST /api/v1/auth/login}); the dashboard reads the same data through {@link NotesInternalResource}. The write endpoints share one
 * implementation with the web UI — both surfaces call the same {@link NoteService}, so the write rules (the shared text pipeline's length and content
 * checks, a blank note removing the day's row, a future date being allowed) cannot diverge; this resource only translates {@link NoteResult}
 * outcomes into JSON. The {@code q} filter is the {@code /notes} page's search, over the same {@link NoteService} method — the surfaces differ only
 * in what they select and how they order it (this one a date range, earliest first; the page the whole history, latest first), never in what counts
 * as a match.
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
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
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
     * Returns the user's notes, earliest first — optionally narrowed to a date range and/or to those containing a search term — or {@code 304} when
     * the result is unchanged since the caller's ETag.
     *
     * @param start      inclusive start of the range (ISO-8601 date); omitted together with {@code end} covers the whole history
     * @param end        inclusive end of the range (ISO-8601 date); omitted together with {@code start} covers the whole history
     * @param searchTerm the optional case-insensitive content filter
     * @param pageNum    the 1-based page to return (out of range is rejected, never clamped)
     * @param request    the JAX-RS request, used to evaluate the {@code If-None-Match} conditional against the result's ETag
     * @return the matching notes, or an empty {@code 304} response
     */
    @Compressed
    @GET
    @Operation(
        summary = "List or search notes",
        description = "Returns one page of the user's notes, earliest first. Days with no note are simply absent, so the "
        + "result is exactly the set of days that have one. Give 'start' and 'end' to restrict the result to a date range, or omit BOTH to cover "
        + "the user's whole history; give 'q' to keep only the notes whose content contains that text, case-insensitively. Paginated at a fixed 31 "
        + "notes per page - one full calendar month, so the usual 'give me this month' call is a single page - because a note may run to 10,000 "
        + "characters and an unbounded range would be the largest response the API can produce. An out-of-range page is rejected with a 400 (never "
        + "silently clamped)."
    )
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "One page of the matching notes, earliest first.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = NotesPageDto.class))),
        @APIResponse(responseCode = "304", description = "Not modified: the result is unchanged since the ETag in the 'If-None-Match' request "
                + "header, so no body is returned."),
        @APIResponse(responseCode = "400", description = "Only one of 'start' and 'end' was given, or one of them is not a valid ISO-8601 date, or "
                + "the requested page is out of range.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    })
    public Response notes(
        @Parameter(name = "start", in = ParameterIn.QUERY,
        description = "Inclusive start of the range, as an ISO-8601 date (yyyy-MM-dd); only the date part is used. Omit together with 'end' "
        + "to cover the whole history.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-01"))
        @QueryParam("start") final @Nullable String start,
        @Parameter(name = "end", in = ParameterIn.QUERY,
        description = "Inclusive end of the range, as an ISO-8601 date (yyyy-MM-dd); only the date part is used. Omit together with 'start' "
        + "to cover the whole history.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-30"))
        @QueryParam("end") final @Nullable String end,
        @Parameter(name = "q", in = ParameterIn.QUERY,
        description = "Keep only the notes whose content contains this text, matched case-insensitively as a plain substring (no word "
        + "boundaries, no stemming). Blank or omitted keeps every note.",
        schema = @Schema(type = SchemaType.STRING, examples = "5k"))
        @QueryParam("q") @DefaultValue("") final String searchTerm,
        @Parameter(name = "page", in = ParameterIn.QUERY,
        description = "The 1-based page of the result to return, 31 notes per page. Defaults to the first page.",
        schema = @Schema(type = SchemaType.INTEGER, examples = "1"))
        @QueryParam("page") @DefaultValue("1") final int pageNum,
        @Context final Request request) {

        final User user = currentUser.get();
        final DateWindow window = window(start, end);

        // The response carries nothing but the notes themselves, so the notes' own signature - plus everything that selects among them - is the
        // whole validator. The search term is part of it: two different terms over an unchanged journal are different bodies.
        final ChangeSignature version = window == null ? Note.version(user.id) : Note.rangeVersion(user.id, window.start(), window.end());
        final EntityTag tag = EntityTags.weak(user.id, window, searchTerm, pageNum, version);
        final Response.ResponseBuilder notModified = request.evaluatePreconditions(tag);
        if (notModified != null) {
            return EntityTags.withPrivateValidator(notModified, tag).build();
        }

        // One key opens every note, so it is resolved once rather than per note. A note that will not open is
        // dropped rather than reported: one damaged row must not fail the range for every other day in it.
        // The unbounded read is reversed because its finder is ordered for the notes page (newest first) while this endpoint's published contract is
        // earliest-first. A sentinel-dated call to the ranged finder would avoid the flip, but there is no date bound safely outside every real
        // note_date - LocalDate.MIN/MAX are far outside what the DATE column can even hold.
        final List<Note> stored = window == null
            ? Note.findByUser(user.id).reversed()
            : Note.findByUserAndRange(user.id, window.start(), window.end());
        final List<NoteDto> all = noteService.search(user.id, searchTerm, stored)
            .stream()
            .map(hit -> new NoteDto(hit.date().toString(), hit.content()))
            .toList();
        final PageWindow pageWindow = Pages.window(all.size(), pageNum, PAGE_SIZE);

        // Surface input policy, matching every other paginated API endpoint: an out-of-range page is REJECTED rather than clamped, so a page number
        // is never silently answered with some other page. Page 1 of an empty range is legal and returns nothing. Past this guard the requested page
        // is in range, so the clamp the shared window applied for the web surface has left it exactly as asked for.
        if (pageNum < 1 || pageNum > Math.max(1, pageWindow.totalPages())) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse("Page " + pageNum + " is out of range"))
                .build();
        }

        final List<NoteDto> items = Pages.slice(all, pageWindow);
        // The COUNT and the window only - never a note's content, and never what was searched for; see NoteService's logging rule.
        LOGGER.debug("Notes API read {} of {} note(s) over {} (page {}) for user {}",
            items.size(), all.size(), window == null ? "the whole history" : window.start() + " to " + window.end(), pageNum, user.email);
        return EntityTags.withPrivateValidator(Response.ok(new NotesPageDto(items, all.size(), pageWindow.totalPages(), pageNum)), tag).build();
    }

    // Surface input policy: the range is optional, but it is a RANGE - half of one is a request the caller did not mean to make, so requireDate
    // rejects it with a 400 rather than quietly completing it with "the beginning of time" or "today" (the reject-never-coerce rule for /api/v1).
    @Nullable
    private static DateWindow window(final @Nullable String start, final @Nullable String end) {
        if ((start == null || start.isBlank()) && (end == null || end.isBlank())) {
            return null;
        }
        return new DateWindow(DateRanges.requireDate("start", start), DateRanges.requireDate("end", end));
    }

    private record DateWindow(LocalDate start, LocalDate end) {

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
    record NotesPageDto(
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
    // Public is forced: Quarkus's generated (de)serializer is not a nestmate so private throws IllegalAccessError, and the endpoint
    // taking it must be public for JAX-RS, so package-private would trip ClassEscapesItsScope instead.
    @Schema(description = "The content to write for the day.")
    @SuppressWarnings({"unused", "WeakerAccess"}) // JSON request body: the canonical constructor is invoked reflectively by Jackson, never from Java
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
    record NoteDto(
        @Schema(examples = "2026-06-15", description = "The day, as an ISO-8601 date string.") String date,
        @Schema(examples = "Ran 5k before work.", description = "The note content, in its stored form.") String content) {

        private static NoteDto of(final Note note, final String content) {
            return new NoteDto(note.noteDate.toString(), content);
        }
    }
}
