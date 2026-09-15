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

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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
import java.util.UUID;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.log.DateRanges;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.text.TextOutcomeExtensions;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
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
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jspecify.annotations.Nullable;

/**
 * The public REST API for the files attached to a day's note — the twin of {@link NoteAttachmentsInternalResource}, sharing the same
 * {@link NoteAttachmentService} so the rules cannot diverge between the two surfaces.
 *
 * <p>
 * <strong>An attachment is embedded in the note's own text</strong>, by a {@code [[name]]} token this API hands back on every write. A client that
 * uploads a file and never writes that token into the note has stored something the note does not mention, and the next save of that day will
 * collect it — see {@link NoteAttachmentService} for why a save is what settles which files are still embedded.
 *
 * <p>
 * <strong>A file is uploaded as a raw request body, not as a multipart form</strong>, with its name in the {@code filename} query parameter. That
 * matches {@code POST /api/v1/data/import}, which takes its archive the same way. The body is bounded by {@code MAX_ATTACHMENT_SIZE} (25 MB by
 * default), a ceiling of this endpoint's own rather than the 1 MB every ordinary endpoint is held to
 * exactly as every other non-import endpoint is, and a larger one is answered with a {@code 413} before it is read.
 */
@Tag(name = "Note attachments", description = "Attach files to a day's note, and rename, download or remove them.")
@Path("/api/v1/notes/{date}/attachments")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
@Produces(MediaType.APPLICATION_JSON)
@RollbackOnErrorStatus
public class NoteAttachmentsApiResource {

    private static final Logger LOGGER = LogManager.getLogger(NoteAttachmentsApiResource.class);

    private final AttachmentPolicy attachmentPolicy;
    private final CurrentUser currentUser;
    private final NoteAttachmentService noteAttachmentService;

    /**
     * Injects the configured extension policy, the current-user accessor and the shared attachment service.
     *
     * @param attachmentPolicy      the configured set of acceptable file extensions, named in a refusal
     * @param currentUser           the current-user accessor
     * @param noteAttachmentService the shared attachment service
     */
    @Inject
    public NoteAttachmentsApiResource(final AttachmentPolicy attachmentPolicy, final CurrentUser currentUser,
        final NoteAttachmentService noteAttachmentService) {
        this.attachmentPolicy = attachmentPolicy;
        this.currentUser = currentUser;
        this.noteAttachmentService = noteAttachmentService;
    }

    /**
     * Lists the files attached to a day's note.
     *
     * @param date the day to read, as an ISO-8601 date
     * @return the day's attachments
     */
    @GET
    @Operation(
        summary = "List a day's attachments",
        description = "Returns every file attached to the given day's note, oldest first. A day with none answers an empty list rather than a 404, "
        + "because a day with no note can still be asked about. The bytes are not included - fetch one attachment at a time by its ID.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The day's attachments, oldest first.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AttachmentsDto.class)))
    @APIResponse(responseCode = "400", description = "The date is not a valid ISO-8601 date.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    public Response attachments(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to read, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date) {

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        final List<AttachmentDto> items = noteAttachmentService.forDay(user, day).stream()
            .map(NoteAttachmentsApiResource::dto)
            .toList();
        // The COUNT only - a file's NAME is as private as the note it sits in. See NoteAttachmentService.
        LOGGER.debug("Attachments API read {} attachment(s) for {} for user {}", items.size(), day, user.email);
        return Response.ok(new AttachmentsDto(day.toString(), items)).build();
    }

    /**
     * Attaches a file to a day's note.
     *
     * @param date     the day to attach to, as an ISO-8601 date
     * @param filename the file's name
     * @param file     the file's bytes
     * @return the stored attachment
     */
    @POST
    @Consumes(MediaType.WILDCARD)
    @Transactional
    @Operation(
        summary = "Attach a file to a day",
        description = "Stores the request body as a file attached to the given day, under the name given by 'filename'. The day need not have a note "
        + "yet, and may be in the future. The stored name is NOT always the name sent: a path prefix is dropped, square brackets are replaced "
        + "(they delimit the token that embeds the file in the note), an over-long name is shortened without losing its extension, and a collision "
        + "with another file on the same day is resolved by appending ' (2)'. The response carries both the stored name and the exact 'token' text "
        + "to write into the note - a file the note never names is removed the next time that day is saved.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The stored attachment, with the token to embed in the note.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AttachmentDto.class)))
    @APIResponse(responseCode = "400", description = "The date is not a valid ISO-8601 date, the body is empty, the extension is not one this "
        + "deployment accepts, or the name breaks a content rule.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    @APIResponse(responseCode = "413",
        description = "The file is larger than this deployment's attachment limit (MAX_ATTACHMENT_SIZE, 25 MB by default).")
    public Response attach(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day to attach to, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @Parameter(name = "filename", in = ParameterIn.QUERY, required = true, description = "The file's name, including its extension.",
        schema = @Schema(type = SchemaType.STRING, examples = "ticket-stub.png"))
        @QueryParam("filename") final @Nullable String filename,
        @RequestBody(description = "The file's bytes.") final byte @Nullable [] file) {

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return translate(noteAttachmentService.attach(user, day, filename, file));
    }

    /**
     * Downloads one attachment's bytes.
     *
     * @param date the day the attachment belongs to, as an ISO-8601 date
     * @param id   the attachment's ID
     * @return the file
     */
    @GET
    @Path("/{id}")
    @Produces(MediaType.WILDCARD)
    @Operation(
        summary = "Download an attachment",
        description = "Returns the file's bytes. The media type and the Content-Disposition are derived from the STORED NAME rather than from "
        + "anything the uploader sent: a raster image is served as its own type and inline, and everything else is served as "
        + "application/octet-stream with a download disposition, so a file cannot be made to render against this application's origin.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The file's bytes.")
    @APIResponse(responseCode = "400", description = "The date is not a valid ISO-8601 date.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    @APIResponse(responseCode = "404", description = "That day has no attachment with that ID.")
    public Response download(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day the attachment belongs to, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @Parameter(name = "id", in = ParameterIn.PATH, required = true, description = "The attachment's ID.",
        schema = @Schema(type = SchemaType.STRING, format = "uuid"))
        @PathParam("id") final UUID id) {

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return noteAttachmentService.open(user, day, id)
            // The type and the render-or-save decision come from the FILE name, which says what the bytes are; the name the caller saves it under
            // is the DISPLAY name, which is what the user calls it. See AttachmentNames.contentDisposition(String, String).
            .map(opened -> Response.ok(opened.file())
                .type(AttachmentNames.mediaType(opened.fileName()))
                .header(HttpHeaders.CONTENT_DISPOSITION, AttachmentNames.contentDisposition(opened.name(), opened.fileName()))
                .build())
            .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * Renames an attachment, rewriting the day's note with it.
     *
     * @param date    the day the attachment belongs to, as an ISO-8601 date
     * @param id      the attachment's ID
     * @param request the new name
     * @return the renamed attachment
     */
    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(
        summary = "Rename an attachment",
        description = "Changes the attachment's display name AND rewrites every token in that day's note that named it, in one transaction - so the "
        + "writing and the file never disagree. A name already used by another file on the same day is rejected rather than given a ' (2)' suffix, "
        + "because unlike an upload the name is the whole of what was submitted.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The renamed attachment, with the day's note as it now stands.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AttachmentDto.class)))
    @APIResponse(responseCode = "400", description = "The date is not a valid ISO-8601 date, the name breaks a content rule, or that day already "
        + "has an attachment with that name.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    @APIResponse(responseCode = "404", description = "That day has no attachment with that ID.")
    public Response rename(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day the attachment belongs to, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @Parameter(name = "id", in = ParameterIn.PATH, required = true, description = "The attachment's ID.",
        schema = @Schema(type = SchemaType.STRING, format = "uuid"))
        @PathParam("id") final UUID id,
        @RequestBody(description = "The new display name.") final @Nullable RenameRequest request) {

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return translate(noteAttachmentService.rename(user, day, id, request == null ? null : request.name()));
    }

    /**
     * Removes an attachment, taking its token out of the day's note.
     *
     * @param date the day the attachment belongs to, as an ISO-8601 date
     * @param id   the attachment's ID
     * @return {@code 204}
     */
    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(
        summary = "Remove an attachment",
        description = "Deletes the file and removes every token in that day's note that named it, in one transaction. Nothing else about the note is "
        + "rewritten - though the note is stored through the ordinary save path, so its usual normalisation closes the gap the token left.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "204", description = "The attachment was removed.")
    @APIResponse(responseCode = "400", description = "The date is not a valid ISO-8601 date.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    @APIResponse(responseCode = "404", description = "That day has no attachment with that ID.")
    public Response delete(
        @Parameter(name = "date", in = ParameterIn.PATH, required = true, description = "The day the attachment belongs to, as yyyy-MM-dd.",
        schema = @Schema(type = SchemaType.STRING, format = "date", examples = "2026-06-15"))
        @PathParam("date") final String date,
        @Parameter(name = "id", in = ParameterIn.PATH, required = true, description = "The attachment's ID.",
        schema = @Schema(type = SchemaType.STRING, format = "uuid"))
        @PathParam("id") final UUID id) {

        final User user = currentUser.get();
        final LocalDate day = DateRanges.requireDate("date", date);
        return translate(noteAttachmentService.remove(user, day, id));
    }

    private Response translate(final AttachmentResult result) {
        return switch (result) {
            case final AttachmentResult.Attached attached -> Response.ok(dto(attached.attachment())).build();
            case final AttachmentResult.Renamed renamed -> Response.ok(dto(renamed.attachment())).build();
            case final AttachmentResult.Removed _ -> Response.noContent().build();
            case final AttachmentResult.Invalid invalid -> Response.status(Response.Status.BAD_REQUEST)
                .entity(new ApiErrorResponse(TextOutcomeExtensions.message(invalid.failure())))
                .build();
            case final AttachmentResult.Refused refused -> refusalResponse(refused.reason());
        };
    }

    // UNKNOWN_ATTACHMENT is the one refusal that is not a bad request: the caller asked for something that is not there, which is a 404 on every
    // other resource in this API.
    private Response refusalResponse(final AttachmentRefusal reason) {
        if (reason == AttachmentRefusal.UNKNOWN_ATTACHMENT) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(new ApiErrorResponse(AttachmentRefusalExtensions.message(reason, attachmentPolicy.accepted())))
            .build();
    }

    private static AttachmentDto dto(final Attachment attachment) {
        return new AttachmentDto(attachment.id().toString(), attachment.name(), attachment.fileName(), attachment.byteSize(),
            AttachmentNames.previewFor(attachment.fileName()).key(), NoteTokens.embed(attachment.name()));
    }

    /**
     * The body for renaming an attachment.
     *
     * @param name the new display name
     */
    // Public is forced: Quarkus's generated (de)serializer is not a nestmate so private throws IllegalAccessError, and the endpoint
    // taking it must be public for JAX-RS, so package-private would trip ClassEscapesItsScope instead.
    @Schema(description = "The new display name for the attachment.")
    @SuppressWarnings({"unused", "WeakerAccess"}) // JSON request body: the canonical constructor is invoked reflectively by Jackson, never from Java
    public record RenameRequest(
        @Schema(examples = "Berlin ticket.png", description = "The new display name; square brackets are not accepted, since they delimit the token "
        + "that embeds the file in the note.") @Nullable String name) {
    }

    /**
     * One day's attachments.
     *
     * @param date        the day, as an ISO-8601 date string
     * @param attachments the day's attachments, oldest first
     */
    @Schema(description = "Every file attached to one day's note.")
    record AttachmentsDto(
        @Schema(examples = "2026-06-15", description = "The day, as an ISO-8601 date string.") String date,
        @Schema(description = "The day's attachments, oldest first.") List<AttachmentDto> attachments) {

    }

    /**
     * One attached file.
     *
     * @param id       the attachment's ID
     * @param name     the display name, which the note's own text embeds
     * @param fileName the name the file was uploaded under, which a rename leaves alone
     * @param byteSize the file's size in bytes
     * @param preview  what a client may show without downloading the file, and what the file is served inline as
     * @param token    the exact text to write into the note to embed it
     */
    @Schema(description = "One file attached to a day's note.")
    record AttachmentDto(
        @Schema(format = "uuid", description = "The attachment's ID.") String id,
        @Schema(examples = "Berlin ticket.png", description = "The display name, which the note's own text embeds. Renaming changes this and "
        + "nothing else.") String name,
        @Schema(examples = "ticket-stub.png", description = "The name the file was uploaded under, extension and all. Fixed when the file was "
        + "attached - a rename never rewrites it - so it is what the file IS rather than what it is called.") String fileName,
        @Schema(examples = "20481", description = "The file's size in bytes.") int byteSize,
        @Schema(enumeration = {"image", "audio", "none"}, examples = "image",
        description = "What can be shown without downloading the file, decided by the uploaded file name's extension: 'image' for a raster image, "
        + "'audio' for a sound file, 'none' for everything else. Anything other than 'none' is served inline with a real media type; 'none' is "
        + "served as opaque bytes to download.") String preview,
        @Schema(examples = "[[ticket-stub.png]]", description = "The exact text to write into the note to embed this file.") String token) {

    }
}
