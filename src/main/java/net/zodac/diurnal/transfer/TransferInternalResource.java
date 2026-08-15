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

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import net.zodac.diurnal.http.HttpStatus;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * The web UI's internal endpoints for the Settings page's Data card: the import preview the file picker posts to, and the confirmed import behind it.
 * Web-UI plumbing, not part of the public API - that is {@link TransferApiResource}, which shares the same {@link ImportService} so the two surfaces
 * cannot drift on what an archive is allowed to contain or on what importing one does.
 *
 * <p>
 * <strong>There is deliberately no internal export endpoint.</strong> The card's Export button links straight to {@code GET /api/v1/data/export}: a
 * cookie is accepted there, and the bytes it produces are the same bytes an internal twin would produce, so a second endpoint would duplicate the
 * export rather than plumb it.
 *
 * <p>
 * Both endpoints take the <strong>raw archive as the request body</strong> rather than a multipart form, exactly as the public API does. The card
 * sends it with {@code fetch} from {@code settings.js} instead of an htmx attribute, for the reason the login, register and password cards do: a
 * refused archive is an expected, handled outcome, and htmx logs every {@code 4xx} to the console unsuppressably. It also means the Import button can
 * re-send the very bytes the preview was computed from, which is what keeps the two steps stateless - nothing is staged on the server between them.
 *
 * <p>
 * A refusal answers {@code 422} here where the API answers {@code 400}: the same per-surface split every other text input in the app uses. The body
 * is the same rendered panel in every case, so the card simply replaces its contents with whatever comes back.
 */
@Path("/internal/data")
@RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
@Produces(MediaType.TEXT_HTML)
@RollbackOnErrorStatus
@Schema(hidden = true)
public class TransferInternalResource {

    private static final String APPLICATION_ZIP = "application/zip";

    private final CurrentUser currentUser;
    private final ImportService importService;
    private final Template importPanelTemplate;

    /**
     * Injects the current-user accessor, the shared import service and the import panel partial.
     *
     * @param currentUser         the current-user accessor
     * @param importService       the shared import service
     * @param importPanelTemplate the import panel partial template
     */
    @Inject
    public TransferInternalResource(final CurrentUser currentUser, final ImportService importService,
        @Location("partials/import-panel") final Template importPanelTemplate) {
        this.currentUser = currentUser;
        this.importService = importService;
        this.importPanelTemplate = importPanelTemplate;
    }

    /**
     * Validates an uploaded archive and renders what importing it would do, writing nothing.
     *
     * @param archive the uploaded archive bytes
     * @return the rendered panel
     */
    @POST
    @Path("import/preview")
    @Consumes(APPLICATION_ZIP)
    public Response preview(final byte[] archive) {
        return render(importService.preview(currentUser.get(), archive));
    }

    /**
     * Imports an uploaded archive, replacing everything the account holds, and renders the outcome.
     *
     * @param archive the uploaded archive bytes
     * @return the rendered panel
     */
    @POST
    @Path("import")
    @Consumes(APPLICATION_ZIP)
    @Transactional
    public Response apply(final byte[] archive) {
        return render(importService.apply(currentUser.get(), archive));
    }

    private Response render(final ImportResult result) {
        return switch (result) {
            case final ImportResult.Previewed previewed -> panel("preview", previewed.summary(), List.of(), 0, "").build();
            case final ImportResult.Applied applied -> panel("applied", applied.summary(), List.of(), 0, applied(applied.summary())).build();
            // 422 on the web where the API answers 400 - the same per-surface split every other rejected input uses.
            case final ImportResult.Rejected rejected -> panel("rejected", null, rejected.problems(), rejected.totalFound(),
                "The archive was refused, so nothing was changed.").status(HttpStatus.UNPROCESSABLE_ENTITY).build();
            case final ImportResult.Malformed malformed -> panel("rejected", null, List.of(), 0, malformed.reason())
                .status(HttpStatus.UNPROCESSABLE_ENTITY).build();
        };
    }

    // Worded here rather than in the panel, because the success banner is the shared partial and it takes one ready-made string. Every figure still
    // goes through the summary's own extensions, so this cannot pluralise differently from the preview above it.
    private static String applied(final ImportSummary summary) {
        return "Imported " + ImportSummaryExtensions.actionsLabel(summary)
            + ", " + ImportSummaryExtensions.logsLabel(summary)
            + " and " + ImportSummaryExtensions.notesLabel(summary) + ".";
    }

    private Response.ResponseBuilder panel(final String state, final @Nullable ImportSummary summary, final List<ImportProblem> problems,
        final int totalProblems, final String message) {
        return Response.ok(importPanelTemplate.data(
            "state", state,
            "summary", summary,
            "problems", problems,
            "totalProblems", totalProblems,
            "message", message));
    }
}
