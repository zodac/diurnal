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

package net.zodac.diurnal.transfer;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The public REST API for taking a user's data out and putting it back: {@code GET /api/v1/data/export} downloads their whole history as a ZIP of
 * CSV files, {@code POST /api/v1/data/import/preview} says what importing one would do, and {@code POST /api/v1/data/import} does it.
 *
 * <p>
 * Both write endpoints share one implementation with the web UI - both surfaces call the same {@link ImportService}, so the validation rules and the
 * replace semantics cannot diverge; this resource only translates {@link ImportResult} outcomes into JSON. The Settings page's own export button
 * points straight at this endpoint rather than at an {@code /internal} twin, because the archive is identical on both surfaces and a second endpoint
 * producing the same bytes would be duplication rather than plumbing.
 *
 * <p>
 * The upload is the raw archive as the request body ({@code application/zip}), not a multipart form: it is one file with no fields beside it, so
 * {@code curl --data-binary @diurnal-export.zip} is the whole call, and the browser sends exactly the same bytes to the internal twin.
 *
 * <p>
 * <strong>The exported archive holds note content in the clear.</strong> Notes are encrypted at rest, and an export necessarily opens them - see
 * {@link ExportService}.
 */
@Tag(name = "Data", description = "Export a user's whole history as an editable archive, and import one back.")
@Path("/api/v1/data")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
@Produces(MediaType.APPLICATION_JSON)
@RollbackOnErrorStatus
public class TransferApiResource {

    private static final String APPLICATION_ZIP = "application/zip";

    private final CurrentUser currentUser;
    private final ExportService exportService;
    private final ImportService importService;

    /**
     * Injects the current-user accessor and the shared export and import services.
     *
     * @param currentUser    the current-user accessor
     * @param exportService  the shared export service
     * @param importService  the shared import service
     */
    @Inject
    public TransferApiResource(final CurrentUser currentUser, final ExportService exportService, final ImportService importService) {
        this.currentUser = currentUser;
        this.exportService = exportService;
        this.importService = importService;
    }

    /**
     * Downloads the user's whole history as a ZIP archive of CSV files.
     *
     * @return the archive, as an attachment
     */
    @GET
    @Path("export")
    @Produces(APPLICATION_ZIP)
    @Operation(
        summary = "Export all data",
        description = "Downloads the user's actions, day counts and day notes as a ZIP archive holding three CSV files - actions.csv "
        + "(name, colour), logs.csv (date, action, count) and notes.csv (date, content). A log names its action by name rather than by any "
        + "internal identifier, so the archive can be edited in a spreadsheet and imported back. Note content is written in PLAIN TEXT: it is "
        + "encrypted in the database, and exporting it necessarily decrypts it, so the downloaded file has none of that protection."
    )
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The archive, as an attachment.",
        content = @Content(mediaType = APPLICATION_ZIP, schema = @Schema(type = SchemaType.STRING, format = "binary")))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    public Response export() {
        final User user = currentUser.get();
        return Response.ok(exportService.export(user))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + exportService.fileName(user) + "\"")
            .build();
    }

    /**
     * Reports what importing the given archive would do, without writing anything.
     *
     * @param archive the uploaded archive bytes
     * @return the summary, or the reasons the archive was refused
     */
    @POST
    @Path("import/preview")
    @Consumes(APPLICATION_ZIP)
    @Operation(
        summary = "Preview an import",
        description = "Reads and fully validates an export archive and reports what importing it would do, WITHOUT writing anything. Every "
        + "rule the real import applies is applied here, so an archive this accepts is one the import will accept. The reply also states how "
        + "much the account currently holds, since an import replaces all of it."
    )
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The archive is valid; nothing was written.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ImportSummaryDto.class)))
    @APIResponse(responseCode = "400", description = "The archive was refused - it is not a readable ZIP, it is missing a member, or at "
        + "least one row is invalid. The reply locates every problem it can.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ImportRejectionDto.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    public Response preview(
        @RequestBody(description = "The export archive to validate.",
        content = @Content(mediaType = APPLICATION_ZIP, schema = @Schema(type = SchemaType.STRING, format = "binary")))
        final byte[] archive) {

        return respond(importService.preview(currentUser.get(), archive));
    }

    /**
     * Imports the given archive, replacing everything the account holds.
     *
     * @param archive the uploaded archive bytes
     * @return the summary of what was written, or the reasons the archive was refused
     */
    @POST
    @Path("import")
    @Consumes(APPLICATION_ZIP)
    @Transactional
    @Operation(
        summary = "Import all data",
        description = "Imports an export archive, REPLACING everything the account holds: every existing action, day count and note is "
        + "removed and the archive's contents are written in their place. The archive must hold all three members of a complete export. It is "
        + "accepted or refused as a whole - if any row is invalid, nothing at all is written - so a refused import leaves the account exactly as "
        + "it was. Call the preview endpoint first to see what will change."
    )
    @SecurityRequirement(name = "BearerAuth")
    @APIResponse(responseCode = "200", description = "The archive was imported; the account now holds exactly what it described.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ImportSummaryDto.class)))
    @APIResponse(responseCode = "400", description = "The archive was refused, so nothing was written - it is not a readable ZIP, it is "
        + "missing a member, or at least one row is invalid. The reply locates every problem it can.",
        content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = ImportRejectionDto.class)))
    @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token.")
    public Response importData(
        @RequestBody(description = "The export archive to import.",
        content = @Content(mediaType = APPLICATION_ZIP, schema = @Schema(type = SchemaType.STRING, format = "binary")))
        final byte[] archive) {

        return respond(importService.apply(currentUser.get(), archive));
    }

    private static Response respond(final ImportResult result) {
        return switch (result) {
            case final ImportResult.Previewed previewed -> Response.ok(ImportSummaryDto.from(previewed.summary())).build();
            case final ImportResult.Applied applied -> Response.ok(ImportSummaryDto.from(applied.summary())).build();
            // Both refusals answer 400 with the same shape - the API's own half of the per-surface split (the internal twin says 422). A malformed
            // upload is simply a refusal with nothing to locate, so it carries an empty problem list rather than a different body.
            case final ImportResult.Malformed malformed -> Response.status(Response.Status.BAD_REQUEST)
                .entity(new ImportRejectionDto(ImportService.message(malformed.reason()), List.of(), 1))
                .build();
            case final ImportResult.Rejected rejected -> Response.status(Response.Status.BAD_REQUEST)
                .entity(ImportRejectionDto.from(rejected))
                .build();
        };
    }

    /**
     * What an import did, or would do.
     *
     * @param actions         the actions the archive brings
     * @param logs            the day counts the archive brings
     * @param notes           the day notes the archive brings
     * @param replacedActions the actions the account held before the import
     * @param replacedLogs    the day counts the account held before the import
     * @param replacedNotes   the day notes the account held before the import
     */
    @Schema(description = "What an import did, or would do.")
    record ImportSummaryDto(
        @Schema(examples = "12", description = "The actions the archive brings.") int actions,
        @Schema(examples = "340", description = "The day counts the archive brings.") int logs,
        @Schema(examples = "88", description = "The day notes the archive brings.") int notes,
        @Schema(examples = "4", description = "The actions the account held before the import.") int replacedActions,
        @Schema(examples = "120", description = "The day counts the account held before the import.") int replacedLogs,
        @Schema(examples = "30", description = "The day notes the account held before the import.") int replacedNotes) {

        private static ImportSummaryDto from(final ImportSummary summary) {
            return new ImportSummaryDto(summary.actions(), summary.logs(), summary.notes(),
                summary.replacedActions(), summary.replacedLogs(), summary.replacedNotes());
        }
    }

    /**
     * Why an archive was refused, located precisely enough to be corrected.
     *
     * @param message       a human-readable summary of the refusal
     * @param problems      the individual problems, capped at 50
     * @param totalProblems how many problems there were in total, which may exceed the number listed
     */
    @Schema(description = "Why an archive was refused. Nothing was written.")
    record ImportRejectionDto(
        @Schema(examples = "The archive was refused", description = "A human-readable summary of the refusal.") String message,
        @Schema(description = "The individual problems, capped at 50.") List<ImportProblemDto> problems,
        @Schema(examples = "3", description = "How many problems there were in total, which may exceed the number listed.") int totalProblems) {

        private static ImportRejectionDto from(final ImportResult.Rejected rejected) {
            final List<ImportProblemDto> problems = rejected.problems()
                .stream()
                .map(problem -> new ImportProblemDto(problem.file(), problem.line(), ImportService.message(problem.reason())))
                .toList();
            return new ImportRejectionDto("The archive was refused, so nothing was written", problems, rejected.totalFound());
        }
    }

    /**
     * One reason a row was refused.
     *
     * @param file   the archive member the problem is in
     * @param line   the 1-based line, or 0 when the problem is with the member as a whole
     * @param reason the human-readable cause
     */
    @Schema(description = "One reason a row was refused.")
    record ImportProblemDto(
        @Schema(examples = "logs.csv", description = "The archive member the problem is in.") String file,
        @Schema(examples = "42", description = "The 1-based line, or 0 when the problem is with the member as a whole.") int line,
        @Schema(examples = "The count must be between 1 and 999.", description = "The human-readable cause.") String reason) {

    }
}
