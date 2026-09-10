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
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.i18n.MessageBundles;
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
import java.util.Locale;
import java.util.stream.Collectors;
import net.zodac.diurnal.http.HttpStatus;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.text.TextFailureBanner;
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
 *
 * <p>
 * The two header-row refusals ({@link ImportReason.EmptyFile}/{@link ImportReason.WrongHeader}) are the one place this resource composes markup
 * rather than leaving it to a template: their column names have to be substituted into a translated sentence as a single value, and Qute cannot
 * build one out of a loop on the way into a {@code msg:} expression. Each name is set in its own {@code <code>} chip and the separating commas are
 * left as plain sentence text, so a chip marks exactly one column name rather than running the whole list together - and the separator stays the
 * ASCII comma in every language, because chips and commas together spell the literal header row the file has to carry (see .claude/I18N.md on the
 * CSV format being a never-translated cross-language contract). The names are {@link TransferFiles} constants, never anything from the upload, which
 * is what keeps the composed markup safe to hand to a {@code .raw} entry.
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
    private final Template importReasonTemplate;
    private final TextFailureBanner textFailureBanner;

    /**
     * Injects the current-user accessor, the shared import service and the import panel partial and the two partials that translate an
     * {@link ImportReason}.
     *
     * @param currentUser                the current-user accessor
     * @param importService              the shared import service
     * @param importPanelTemplate        the import panel partial template
     * @param importReasonTemplate       the translated import-refusal-reason partial template
     * @param textFailureBanner the shared text-pipeline rejection sentence renderer
     */
    @Inject
    public TransferInternalResource(final CurrentUser currentUser, final ImportService importService,
        @Location("partials/import-panel") final Template importPanelTemplate,
        @Location("partials/import-reason") final Template importReasonTemplate,
        final TextFailureBanner textFailureBanner) {
        this.currentUser = currentUser;
        this.importService = importService;
        this.importPanelTemplate = importPanelTemplate;
        this.importReasonTemplate = importReasonTemplate;
        this.textFailureBanner = textFailureBanner;
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
        final var user = currentUser.get();
        return render(importService.preview(user, archive), user.locale());
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
        final var user = currentUser.get();
        return render(importService.apply(user, archive), user.locale());
    }

    private Response render(final ImportResult result, final Locale locale) {
        return switch (result) {
            case final ImportResult.Previewed previewed -> panel("preview", previewed.summary(), List.of(), 0, "", locale).build();
            case final ImportResult.Applied applied -> panel("applied", applied.summary(), List.of(), 0, "", locale).build();
            // 422 on the web where the API answers 400 - the same per-surface split every other rejected input uses. An empty message
            // means the generic translated refusal banner (see import-panel.html).
            case final ImportResult.Rejected rejected ->
                panel("rejected", null, translatedProblems(rejected.problems(), locale), rejected.totalFound(), "", locale)
                    .status(HttpStatus.UNPROCESSABLE_ENTITY).build();
            case final ImportResult.Malformed malformed ->
                panel("rejected", null, List.of(), 0, importReasonBanner(malformed.reason(), locale), locale)
                    .status(HttpStatus.UNPROCESSABLE_ENTITY).build();
        };
    }

    private Response.ResponseBuilder panel(final String state, final @Nullable ImportSummary summary, final List<ImportProblemView> problems,
        final int totalProblems, final String message, final Locale locale) {
        return Response.ok(importPanelTemplate.data(
            "state", state,
            "summary", summary,
            "problems", problems,
            "totalProblems", totalProblems,
            "message", message)
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale));
    }

    private List<ImportProblemView> translatedProblems(final List<ImportProblem> problems, final Locale locale) {
        return problems.stream()
            .map(problem -> new ImportProblemView(memberName(problem), problem.line(), importReasonBanner(problem.reason(), locale)))
            .toList();
    }

    // A missing member is the one problem that belongs to no member: ArchiveParser files it against the archive itself, which the row would
    // otherwise lead with as a literal, untranslated "archive" in front of a sentence that already names the absent file. An empty name is the
    // row's own signal to lead with nothing at all - see partials/import-panel.html.
    private static String memberName(final ImportProblem problem) {
        return problem.reason() instanceof ImportReason.MissingMember ? "" : problem.file();
    }

    private String importReasonBanner(final ImportReason reason, final Locale locale) {
        return importReason(reason).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
    }

    /*
     * One exhaustive arm per ImportReason variant, so its length/coupling is the size of the catalogue rather than complexity - see
     * ImportService.message's identical shape (the API's own composer over the same sealed type) for why splitting it is worse. Each arm names only
     * the partial and the values that arm carries; binding the locale and rendering is the caller's single line below, so the twenty arms cannot
     * disagree about it.
     */
    @SuppressWarnings({"OverlyLongMethod", "OverlyCoupledMethod"})
    private TemplateInstance importReason(final ImportReason reason) {
        return switch (reason) {
            case final ImportReason.NotZipArchive _ -> importReasonTemplate.data("kind", "notZipArchive");
            case final ImportReason.TooManyEntries tooMany -> importReasonTemplate.data("kind", "tooManyEntries", "maxEntries",
                tooMany.maxEntries());
            case final ImportReason.ArchiveTooLarge _ -> importReasonTemplate.data("kind", "archiveTooLarge");
            case final ImportReason.ArchiveUnreadable unreadable -> importReasonTemplate.data("kind", "archiveUnreadable", "detail",
                String.valueOf(unreadable.detail()));
            case final ImportReason.CsvUnreadable _ -> importReasonTemplate.data("kind", "csvUnreadable");
            case final ImportReason.MissingMember missing -> importReasonTemplate.data("kind", "missingMember", "file", missing.file());
            case final ImportReason.EmptyFile empty -> importReasonTemplate.data("kind", "emptyFile", "header", chippedColumns(empty.columns()));
            case final ImportReason.WrongHeader wrongHeader -> importReasonTemplate.data("kind", "wrongHeader", "header",
                chippedColumns(wrongHeader.columns()));
            case final ImportReason.WrongColumnCount wrongCount -> importReasonTemplate.data("kind", "wrongColumnCount", "expected",
                wrongCount.expected(), "actual", wrongCount.actual());
            case final ImportReason.InvalidColour _ -> importReasonTemplate.data("kind", "invalidColour");
            case final ImportReason.DuplicateAction duplicate -> importReasonTemplate.data("kind", "duplicateAction", "name", duplicate.name());
            case final ImportReason.FutureLog futureLog -> importReasonTemplate.data("kind", "futureLog", "date", futureLog.date().toString());
            case final ImportReason.UnknownAction unknown -> importReasonTemplate.data("kind", "unknownAction", "actionName",
                unknown.actionName());
            case final ImportReason.NonNumericCount nonNumeric -> importReasonTemplate.data("kind", "nonNumericCount", "raw", nonNumeric.raw());
            case final ImportReason.CountOutOfRange outOfRange -> importReasonTemplate.data("kind", "countOutOfRange", "max", outOfRange.max());
            case final ImportReason.DuplicateLog duplicate -> importReasonTemplate.data("kind", "duplicateLog", "actionName",
                duplicate.actionName(), "date", duplicate.date().toString());
            case final ImportReason.EmptyNote emptyNote -> importReasonTemplate.data("kind", "emptyNote", "date", emptyNote.date().toString());
            case final ImportReason.DuplicateNote duplicate -> importReasonTemplate.data("kind", "duplicateNote", "date",
                duplicate.date().toString());
            case final ImportReason.InvalidDate invalidDate -> importReasonTemplate.data("kind", "invalidDate", "raw", invalidDate.raw());
            // The one arm that is not this partial at all: a refused free-text value is worded by the shared text pipeline's own sentence, exactly
            // as ProfileRejection and RegistrationError word theirs. It binds the locale and renders through the same tail as every arm above.
            case final ImportReason.InvalidTextField invalid -> textFailureBanner.instance(invalid.failure());
        };
    }

    private static String chippedColumns(final List<String> columns) {
        return columns.stream()
            .map(column -> "<code>" + column + "</code>")
            .collect(Collectors.joining(", "));
    }

    /**
     * One located problem, with its reason already resolved to translated text - {@code import-panel.html} reads this exactly as it read
     * {@link ImportProblem} before the reason became structured.
     *
     * @param file   the archive member the problem is in, or empty when the problem is with the archive as a whole
     * @param line   the 1-based line, or {@code 0} when the problem is with the member as a whole rather than a row in it
     * @param reason the translated cause
     */
    private record ImportProblemView(String file, int line, String reason) {

    }
}
