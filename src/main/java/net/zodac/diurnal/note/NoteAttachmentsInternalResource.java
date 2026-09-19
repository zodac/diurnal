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

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.i18n.MessageBundles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.zodac.diurnal.http.AppPaths;
import net.zodac.diurnal.http.HttpStatus;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.log.DateRanges;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.text.TextFailureBanner;
import net.zodac.diurnal.text.TextValidation;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizes;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The web UI's internal endpoints for note attachments: the notes page's attachments-table fragment, the per-day listing the note box reads, the
 * upload the drop zone posts to, the rename and delete the hover card offers, and the bytes its preview and download link ask for. Web-UI plumbing
 * rather than the public API — that is {@link NoteAttachmentsApiResource}, which shares the same {@link NoteAttachmentService} so the rules cannot
 * diverge.
 *
 * <p>
 * <strong>An upload is a RAW body, not a multipart form</strong>, with the file's own name in a query parameter. The archive import already works
 * this way ({@code TransferInternalResource}); it keeps the application off a multipart extension it has no other use for, and lets the note box
 * report progress: {@code XMLHttpRequest.upload.onprogress} measures the body it is sending, whatever shape that body has. The size ceiling is the
 * one every other endpoint already has - {@code app.http.max-request-body}, refused as a {@code 413} by {@code http.RequestBodyLimitFilter} before
 * the body is read - so there is no second limit to keep in step with the first.
 *
 * <p>
 * <strong>Uploading does not write the note.</strong> It stores the file and answers with the token to embed; the token goes into the box at the
 * user's caret and reaches the server when they save. Renaming and deleting DO write the note, because both change writing that is already stored —
 * see {@link NoteAttachmentService}.
 *
 * <p>
 * A rejection is a {@code 422} here where the API answers {@code 400}, and is worded as a whole translated sentence — the same per-surface split
 * every other text input in the app uses.
 */
@Path("/internal/note-attachments")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
@Produces(MediaType.APPLICATION_JSON)
@RollbackOnErrorStatus
public class NoteAttachmentsInternalResource {

    private static final Logger LOGGER = LogManager.getLogger(NoteAttachmentsInternalResource.class);

    private final AppPaths appPaths;
    private final AttachmentPolicy attachmentPolicy;
    private final Template attachmentRefusalTemplate;
    private final Template attachmentsListTemplate;
    private final CurrentUser currentUser;
    private final NoteAttachmentService noteAttachmentService;
    private final TextFailureBanner textFailureBanner;

    /**
     * Injects the URL builder, the configured extension policy, the refusal-sentence partial, the current-user accessor, the shared attachment
     * service and the shared text-pipeline rejection renderer.
     *
     * @param appPaths                  the single builder of every application URL, for each attachment's own file URL
     * @param attachmentPolicy          the configured set of acceptable file extensions, named in a refusal
     * @param attachmentRefusalTemplate the translated attachment-refusal sentence partial
     * @param attachmentsListTemplate   the notes page's attachments-table partial
     * @param currentUser               the current-user accessor
     * @param noteAttachmentService     the shared attachment service
     * @param textFailureBanner         the shared text-pipeline rejection sentence renderer
     */
    @Inject
    public NoteAttachmentsInternalResource(final AppPaths appPaths, final AttachmentPolicy attachmentPolicy,
        @Location("partials/attachment-refusal") final Template attachmentRefusalTemplate,
        @Location("partials/attachments-list") final Template attachmentsListTemplate, final CurrentUser currentUser,
        final NoteAttachmentService noteAttachmentService, final TextFailureBanner textFailureBanner) {
        this.appPaths = appPaths;
        this.attachmentPolicy = attachmentPolicy;
        this.attachmentRefusalTemplate = attachmentRefusalTemplate;
        this.attachmentsListTemplate = attachmentsListTemplate;
        this.currentUser = currentUser;
        this.noteAttachmentService = noteAttachmentService;
        this.textFailureBanner = textFailureBanner;
    }

    /**
     * Returns the notes page's attachments-table partial - one page of the account's attachments, filtered by the table's own search term - for the
     * HTMX swap behind that box and its pagination links.
     *
     * <p>
     * A literal path segment beside this resource's {@code /{date}} routes, which JAX-RS prefers over the template for this exact URL, exactly as
     * {@code /internal/notes/list} sits beside {@code /internal/notes/{date}}.
     *
     * @param searchTerm the optional case-insensitive filename filter
     * @param pageNum    the 1-based page to render (clamped into range)
     * @return the rendered list partial
     */
    @GET
    @Path("list")
    @Produces(MediaType.TEXT_HTML)
    public Response list(
        @QueryParam("q") @DefaultValue("") final String searchTerm,
        @QueryParam("page") @DefaultValue("1") final int pageNum) {

        final User user = currentUser.get();
        final Locale locale = user.locale();
        // The same page size as the notes table above it: the two are halves of one page, so one preference governs both rather than the Settings
        // list growing a second row for a table nobody would size separately.
        final PaginatedAttachmentHits hits =
            noteAttachmentService.searchPage(user, searchTerm, pageNum, PageSizes.forSection(user, PageSection.NOTES));
        final PaginatedAttachments page = AttachmentPages.of(hits, TextValidation.searchTerm(searchTerm), locale, appPaths);
        // The COUNT only - a file's NAME is as private as the note it sits in, and so is what was searched for. See NoteAttachmentService.
        LOGGER.trace("Attachments table served {} of {} attachment(s) for user {}", page.items().size(), page.totalCount(), user.email);
        return Response.ok(attachmentsListTemplate.data("page", page, "searching", !searchTerm.isBlank())
                .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale)).build();
    }

    /**
     * Returns one day's attachments, which the note box reads whenever the selected day changes.
     *
     * @param date the day to read
     * @return the day's attachments
     */
    @GET
    @Path("/{date}")
    public Response forDay(@PathParam("date") final String date) {
        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        final List<AttachmentDto> attachments = dtos(day, noteAttachmentService.forDay(user, day));
        // The COUNT only - a file's NAME is as private as the note it sits in. See NoteAttachmentService.
        LOGGER.trace("Attachment feed served {} attachment(s) for {} for user {}", attachments.size(), day, user.email);
        return Response.ok(new DayAttachments(day.toString(), attachments)).build();
    }

    /**
     * Stores an uploaded file against a day and answers with the token to embed in the note.
     *
     * @param date     the day to attach to
     * @param filename the file's own name, as the browser reported it
     * @param file     the uploaded bytes
     * @return the stored attachment, or {@code 422} with the refusal
     */
    @POST
    @Path("/{date}")
    @Consumes(MediaType.WILDCARD)
    @Transactional
    public Response upload(
        @PathParam("date") final String date,
        @QueryParam("filename") final @Nullable String filename,
        final byte @Nullable [] file) {

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return translate(noteAttachmentService.attach(user, day, filename, file), user.locale());
    }

    /**
     * Renames an attachment, rewriting the day's note with it.
     *
     * @param date    the day the attachment belongs to
     * @param id      the attachment's id
     * @param request the new name
     * @return the renamed attachment and the note as it now stands, or {@code 422} with the refusal
     */
    @POST
    @Path("/{date}/{id}/rename")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Response rename(@PathParam("date") final String date, @PathParam("id") final UUID id, final @Nullable RenameSubmission request) {
        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return translate(noteAttachmentService.rename(user, day, id, request == null ? null : request.name()), user.locale());
    }

    /**
     * Deletes an attachment, removing its token from the day's note.
     *
     * @param date the day the attachment belongs to
     * @param id   the attachment's id
     * @return the note as it now stands, or {@code 422} with the refusal
     */
    @POST
    @Path("/{date}/{id}/delete")
    @Transactional
    public Response delete(@PathParam("date") final String date, @PathParam("id") final UUID id) {
        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return translate(noteAttachmentService.remove(user, day, id), user.locale());
    }

    /**
     * Serves one attachment's bytes — what the hover card's image preview loads and what its download link points at.
     *
     * <p>
     * The media type and the disposition are both derived from a stored NAME rather than from anything the uploader sent, so only a raster image is
     * ever served as something the browser will render; see {@link AttachmentNames#mediaType(String)}. The two names do different jobs here: the
     * type comes from the FILE name, which says what the bytes are and which a rename cannot change, while the {@code filename} the browser saves it
     * under is the DISPLAY name, which is what the user calls it.
     *
     * @param date the day the attachment belongs to
     * @param id   the attachment's id
     * @return the file, or {@code 404} when the user has no such attachment
     */
    @GET
    @Path("/{date}/{id}/file")
    @Produces(MediaType.WILDCARD)
    public Response file(@PathParam("date") final String date, @PathParam("id") final UUID id) {
        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return noteAttachmentService.open(user, day, id)
            .map(opened -> Response.ok(opened.file())
                .type(AttachmentNames.mediaType(opened.fileName()))
                .header(HttpHeaders.CONTENT_DISPOSITION, AttachmentNames.contentDisposition(opened.name(), opened.fileName()))
                .build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    private Response translate(final AttachmentResult result, final Locale locale) {
        return switch (result) {
            case final AttachmentResult.Attached attached ->
                Response.ok(new StoredAttachment(attached.date().toString(), dto(attached.date(), attached.attachment()), null)).build();
            case final AttachmentResult.Renamed renamed ->
                Response.ok(new StoredAttachment(renamed.date().toString(), dto(renamed.date(), renamed.attachment()), renamed.noteContent()))
                    .build();
            case final AttachmentResult.Removed removed ->
                Response.ok(new StoredAttachment(removed.date().toString(), null, removed.noteContent())).build();
            // 422 on the web where the API answers 400 - the same per-surface split every other text input uses.
            case final AttachmentResult.Invalid invalid -> Response.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .entity(new ApiErrorResponse(textFailureBanner.render(invalid.failure(), locale)))
                .build();
            case final AttachmentResult.Refused refused -> Response.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .entity(new ApiErrorResponse(refusalMessage(refused.reason(), locale)))
                .build();
        };
    }

    // The whole translated sentence, rendered by a partial rather than composed here: a refusal is UI text, and Java cannot resolve a locale-bound
    // message (see .claude/I18N.md). The accepted extensions go in as one pre-joined value, because how many of them there are is a property of the
    // deployment rather than of the language.
    private String refusalMessage(final AttachmentRefusal reason, final Locale locale) {
        return attachmentRefusalTemplate
            .data("kind", reason.name(), "accepted", String.join(", ", attachmentPolicy.accepted()))
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale)
            .render();
    }

    private List<AttachmentDto> dtos(final LocalDate day, final List<Attachment> attachments) {
        return attachments.stream().map(attachment -> dto(day, attachment)).toList();
    }

    private AttachmentDto dto(final LocalDate day, final Attachment attachment) {
        // Previewability is decided by the FILE name, not the display name: what the bytes are cannot be changed by relabelling them, and a rename
        // may legitimately leave the display name with no extension.
        return new AttachmentDto(attachment.id().toString(), attachment.name(), attachment.fileName(), attachment.byteSize(),
            AttachmentNames.previewFor(attachment.fileName()).key(), appPaths.internalNoteAttachmentFile(day, attachment.id()),
            NoteTokens.embed(attachment.name()));
    }

    /**
     * The rename submission. Public because Quarkus generates the Jackson deserialiser as a separate class, which cannot reach a private nested
     * record's canonical constructor ({@code IllegalAccessError} at request time, surfacing as a {@code 500}).
     *
     * @param name the new display name
     */
    @SuppressWarnings({"unused", "WeakerAccess"}) // JSON request body: the canonical constructor is invoked reflectively by Jackson, never from Java
    public record RenameSubmission(@Nullable String name) {

    }

    /**
     * One day's attachments, as the note box reads them.
     *
     * @param date        the day, as an ISO-8601 date string
     * @param attachments the day's attachments, oldest first
     */
    record DayAttachments(String date, List<AttachmentDto> attachments) {

    }

    /**
     * The outcome of a write, carrying whatever the note box has to repaint from.
     *
     * @param date        the day the attachment belongs to, as an ISO-8601 date string
     * @param attachment  the stored attachment, {@code null} when it was just deleted
     * @param noteContent the day's note after the token was rewritten, {@code null} for an upload — which writes no note at all, so the box's own
     *                    unsaved text must not be replaced
     */
    record StoredAttachment(String date, @Nullable AttachmentDto attachment, @Nullable String noteContent) {

    }

    /**
     * One attachment, as every surface of the web UI renders it.
     *
     * @param id       the attachment's id
     * @param name     the display name, which the note's own text embeds
     * @param fileName the name the file was uploaded under, which a rename leaves alone
     * @param byteSize the file's size in bytes
     * @param preview  what the hover card may show without downloading the file, as an {@link AttachmentPreview} key
     * @param url      where to fetch the bytes from
     * @param token    the exact text to write into the note to embed it, so the client never builds the token itself
     */
    record AttachmentDto(String id, String name, String fileName, int byteSize, String preview, String url, String token) {

    }
}
