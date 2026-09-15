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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import net.zodac.diurnal.openapi.ApiErrorResponse;
import net.zodac.diurnal.openapi.ApiPages;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.PageSection;
import net.zodac.diurnal.user.PageSizes;
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
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The public REST API for an account's attachments as a whole — the twin of the notes page's attachments table, sharing the same
 * {@link NoteAttachmentService} so the search rule cannot diverge between the two surfaces.
 *
 * <p>
 * <strong>It sits at its own root rather than under {@code /api/v1/notes}</strong>, which is where every other attachment endpoint lives
 * ({@link NoteAttachmentsApiResource}), because this is the one question about an attachment that is not asked of a DAY: "where is that file" has no
 * date in it, which is the whole reason the page offers it. Reading, writing and removing one all stay per-day, since that is how an attachment is
 * addressed.
 *
 * <p>
 * <strong>A filename is matched by opening it, exactly as a note's content is.</strong> The stored name is sealed under the account's data key, so
 * there is no database predicate for it and no index to page on — see {@link NoteAttachmentService#searchPage(User, String, int, int)}. The bytes
 * are never read here: fetch one file at a time by its day and id.
 *
 * <p>
 * There is deliberately no {@code ETag} on this listing, unlike the notes and actions feeds. A validator has to be derived from a signature the
 * database can produce without reading the rows, and the only such signature for attachments would be a new aggregate query run on every request -
 * paid by every caller to save the ones who poll.
 */
@Tag(name = "Note attachments", description = "Attach files to a day's note, and rename, download or remove them.")
@Path("/api/v1/attachments")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
@Produces(MediaType.APPLICATION_JSON)
public class AttachmentsApiResource {

    private static final Logger LOGGER = LogManager.getLogger(AttachmentsApiResource.class);

    private final CurrentUser currentUser;
    private final NoteAttachmentService noteAttachmentService;

    /**
     * Injects the current-user accessor and the shared attachment service.
     *
     * @param currentUser           the current-user accessor
     * @param noteAttachmentService the shared attachment service
     */
    @Inject
    public AttachmentsApiResource(final CurrentUser currentUser, final NoteAttachmentService noteAttachmentService) {
        this.currentUser = currentUser;
        this.noteAttachmentService = noteAttachmentService;
    }

    /**
     * Lists one page of every file the account holds, newest day first, optionally filtered by a case-insensitive filename search.
     *
     * @param searchTerm the optional case-insensitive filename filter
     * @param pageNum    the 1-based page to return (out of range is rejected, never clamped)
     * @return the requested page of attachments
     */
    @GET
    @Operation(
        summary = "List or search attachments",
        description = "Returns one page of every file attached to any of the user's notes, newest day first, and oldest-attached first within a "
        + "day. Give 'q' to keep only the files whose display name OR uploaded file name contains that text, case-insensitively as a plain "
        + "substring - note content is not searched here (that is GET /api/v1/notes). The page size is the user's 'items per page' preference for "
        + "notes; an out-of-range page is rejected with a 400 (never silently clamped). The bytes are not included - fetch one attachment at a time "
        + "by its day and ID.")
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The requested page of attachments.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = AttachmentPageDto.class)))
    @APIResponse(responseCode = "400", description = "The requested page is out of range.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ApiErrorResponse.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    public Response attachments(
        @Parameter(name = "q", in = ParameterIn.QUERY,
        description = "Keep only the attachments whose display name OR uploaded file name contains this text, matched case-insensitively as a "
        + "plain substring (no word boundaries, no stemming). A match on either is enough, so a file renamed since it was uploaded is still found "
        + "by what it was called. Blank or omitted keeps every attachment.",
        schema = @Schema(type = SchemaType.STRING, examples = "ticket"))
        @QueryParam("q") @DefaultValue("") final String searchTerm,
        @Parameter(name = "page", in = ParameterIn.QUERY, description = "The 1-based page to return (default 1); out-of-range values are rejected.",
        schema = @Schema(type = SchemaType.INTEGER, examples = "1"))
        @QueryParam("page") @DefaultValue("1") final int pageNum) {

        final User user = currentUser.get();
        final PaginatedAttachmentHits hits =
            noteAttachmentService.searchPage(user, searchTerm, pageNum, PageSizes.forSection(user, PageSection.NOTES));

        // Surface input policy: the API rejects an out-of-range page (the web UI clamps it into range), so a page number is never
        // silently answered with some other page.
        final Response outOfRange = ApiPages.outOfRange(pageNum, hits.totalPages());
        if (outOfRange != null) {
            return outOfRange;
        }

        final List<DatedAttachmentDto> items = hits.items().stream().map(AttachmentsApiResource::dto).toList();
        // The COUNT only - a file's NAME is as private as the note it sits in, and so is what was searched for. See NoteAttachmentService.
        LOGGER.debug("Attachments API read {} of {} attachment(s) (page {}) for user {}", items.size(), hits.totalCount(), pageNum, user.email);
        return Response.ok(new AttachmentPageDto(items, hits.totalCount(), hits.totalPages(), pageNum)).build();
    }

    private static DatedAttachmentDto dto(final AttachmentHit hit) {
        final Attachment attachment = hit.attachment();
        return new DatedAttachmentDto(hit.date().toString(), attachment.id().toString(), attachment.name(), attachment.fileName(),
            attachment.byteSize(), AttachmentNames.previewFor(attachment.fileName()).key(), NoteTokens.embed(attachment.name()));
    }

    /**
     * One page of an account's attachments.
     *
     * @param items       the page's attachments, newest day first
     * @param totalCount  the number of attachments matching the search, across all pages
     * @param totalPages  the page count
     * @param currentPage the returned 1-based page (always the requested page - an out-of-range page is rejected, not clamped)
     */
    @Schema(description = "One page of the user's attachments.")
    record AttachmentPageDto(
        @Schema(description = "The page's attachments, newest day first.") List<DatedAttachmentDto> items,
        @Schema(examples = "7", description = "The number of attachments matching the search, across all pages.") int totalCount,
        @Schema(examples = "1", description = "The page count.") int totalPages,
        @Schema(examples = "1", description = "The returned 1-based page.") int currentPage) {

    }

    /**
     * One attached file, with the day it belongs to.
     *
     * @param date     the day the file is attached to, as an ISO-8601 date string
     * @param id       the attachment's ID
     * @param name     the display name, which the note's own text embeds
     * @param fileName the name the file was uploaded under, which a rename leaves alone
     * @param byteSize the file's size in bytes
     * @param preview  what a client may show without downloading the file, and what the file is served inline as
     * @param token    the exact text to write into the note to embed it
     */
    @Schema(description = "One file attached to a day's note, with the day it belongs to.")
    record DatedAttachmentDto(
        @Schema(examples = "2026-06-15", description = "The day the file is attached to, as an ISO-8601 date string.") String date,
        @Schema(format = "uuid", description = "The attachment's ID.") String id,
        @Schema(examples = "Berlin ticket.png", description = "The display name, which the note's own text embeds. Renaming changes this and "
        + "nothing else.") String name,
        @Schema(examples = "ticket-stub.png", description = "The name the file was uploaded under, extension and all. Fixed when the file was "
        + "attached - a rename never rewrites it.") String fileName,
        @Schema(examples = "20481", description = "The file's size in bytes.") int byteSize,
        @Schema(enumeration = {"image", "audio", "none"}, examples = "image",
        description = "What can be shown without downloading the file, decided by the uploaded file name's extension: 'image' for a raster image, "
        + "'audio' for a sound file, 'none' for everything else. Anything other than 'none' is served inline with a real media type; 'none' is "
        + "served as opaque bytes to download.") String preview,
        @Schema(examples = "[[ticket-stub.png]]", description = "The exact text to write into the note to embed this file.") String token) {

    }
}
