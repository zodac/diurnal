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

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.http.NotUiFacing;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.note.Note;
import net.zodac.diurnal.note.NoteField;
import net.zodac.diurnal.note.NoteService;
import net.zodac.diurnal.persistence.LogStatements;
import net.zodac.diurnal.text.TextOutcomeExtensions;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The single owner of the data import, shared by the web UI's HTMX endpoints ({@code TransferInternalResource}) and the public REST API
 * ({@code TransferApiResource}), so a rule added or changed here applies to both surfaces by construction. The resources only translate the returned
 * {@link ImportResult} into their medium.
 *
 * <p>
 * <strong>An import REPLACES.</strong> Every action, day count and note the account holds is removed, and the archive's contents are written in their
 * place - the account ends up holding exactly what the file describes, and nothing else. That is what makes the archive a backup that can actually be
 * restored, and it is also why the operation is worth confirming: {@link #preview(User, byte[])} runs the identical read and validation and stops
 * short of the write, so the confirmation is shown real figures from the real file rather than an estimate.
 *
 * <p>
 * The preview deliberately keeps <strong>no server-side state</strong> - the browser simply sends the same file again to confirm. Staging a parsed
 * archive between two requests would mean holding one user's whole journal, in the clear, in memory or in a table, for as long as they left the tab
 * open; re-reading the upload costs a few milliseconds and holds nothing. It also means the committed import validates the bytes it is about to
 * write, rather than trusting a verdict reached on an earlier request.
 *
 * <p>
 * Writes go through each package's own owner: {@link Note} content is written by {@link NoteService#replaceAll(User, Map)}, which is the only thing
 * that can seal it, and the bulk deletes are the same entity statements {@code AdminUserService} uses to clear an account. Actions are inserted
 * before their logs and flushed, because a log names its action by NAME and the id it needs does not exist until the action row does.
 *
 * <p>
 * The caller owns the transaction: {@link #apply(User, byte[])} must be invoked from a {@code @Transactional} endpoint, so a rejection part-way
 * through rolls back everything the deletes had already done. {@link #preview(User, byte[])} writes nothing and needs none.
 */
@ApplicationScoped
public class ImportService {

    private static final Logger LOGGER = LogManager.getLogger(ImportService.class);

    private final NoteService noteService;
    private final NoteField noteField;
    private final AppClock clock;
    private final LogStatements statements;

    /**
     * Injects the shared notes service, which owns every note write, the configured note field, the application clock, and the database's native
     * statements.
     *
     * @param noteService the shared notes service
     * @param noteField   the configured day-note field every imported note row is validated against
     * @param clock       the application clock for date-boundary logic
     * @param statements  the native action-log statements for the configured database
     */
    @Inject
    public ImportService(final NoteService noteService, final NoteField noteField, final AppClock clock, final LogStatements statements) {
        this.noteService = noteService;
        this.noteField = noteField;
        this.clock = clock;
        this.statements = statements;
    }

    /**
     * Reads and validates an archive without writing anything, reporting what an import of it would do.
     *
     * @param user    the acting user
     * @param archive the uploaded archive bytes
     * @return the outcome, never {@link ImportResult.Applied}
     */
    public ImportResult preview(final User user, final byte[] archive) {
        return read(user, archive, false);
    }

    /**
     * Reads, validates and applies an archive, replacing everything the account holds.
     *
     * <p>
     * Must be called from within a transaction.
     *
     * @param user    the acting user
     * @param archive the uploaded archive bytes
     * @return the outcome, never {@link ImportResult.Previewed}
     */
    public ImportResult apply(final User user, final byte[] archive) {
        return read(user, archive, true);
    }

    // The one code path both surfaces AND both steps take: `commit` decides only whether the last statement runs, so a preview cannot
    // accept an archive the import would then refuse.
    private ImportResult read(final User user, final byte[] archive, final boolean commit) {
        return switch (TransferArchive.unpack(archive)) {
            case final ArchiveOutcome.Malformed malformed -> {
                LOGGER.debug("Import archive refused for user {}: {}", user.email, malformed.reason());
                yield new ImportResult.Malformed(malformed.reason());
            }
            case final ArchiveOutcome.Unpacked unpacked -> validate(user, unpacked.members(), commit);
        };
    }

    private ImportResult validate(final User user, final Map<String, String> members, final boolean commit) {
        // Resolved once for the whole file, in the user's own timezone - the same day boundary a single log write is judged against.
        final LocalDate today = clock.today(clock.zoneFor(user.timezone));

        return switch (ImportParser.parse(members, today, noteField.field())) {
            case final ParseOutcome.Rejected rejected -> {
                LOGGER.warn("Import rejected for user {}: {} problem(s)", user.email, rejected.totalFound());
                yield new ImportResult.Rejected(rejected.problems(), rejected.totalFound());
            }
            case final ParseOutcome.Planned planned -> commitOrPreview(user, planned.plan(), commit);
        };
    }

    private ImportResult commitOrPreview(final User user, final ImportPlan plan, final boolean commit) {
        final ImportSummary summary = new ImportSummary(
            plan.actions().size(), plan.logs().size(), plan.notes().size(),
            Math.toIntExact(Action.count("userId", user.id)),
            Math.toIntExact(ActionLog.count("userId", user.id)),
            Math.toIntExact(Note.count("userId", user.id)));

        if (!commit) {
            return new ImportResult.Previewed(summary);
        }

        write(user, plan);
        // The COUNTS only - never an action name, and never a note's content.
        LOGGER.info("Data imported for user {}: {} action(s), {} log(s), {} note(s), replacing {}/{}/{}",
            user.email, summary.actions(), summary.logs(), summary.notes(),
            summary.replacedActions(), summary.replacedLogs(), summary.replacedNotes());
        return new ImportResult.Applied(summary);
    }

    private void write(final User user, final ImportPlan plan) {
        // Logs before actions: a log has no meaning once its action is gone, and this is the order an account is cleared in elsewhere.
        ActionLog.deleteByUser(user.id);
        Action.delete("userId", user.id);

        final Map<String, UUID> actionIds = new HashMap<>();
        for (final ActionDraft draft : plan.actions()) {
            final Action action = new Action();
            action.userId = user.id;
            action.name = draft.name();
            action.colour = draft.colour();
            action.persist();
            actionIds.put(draft.name(), action.id);
        }

        // The log write below is a native statement, which does not see anything still sitting in the persistence context - so the action rows have
        // to actually be in the database before a log can reference one.
        Panache.getEntityManager().flush();

        // Gathered into parallel lists and written in ONE statement rather than one per entry: a replaced history is ~33,000 entries for a 3-year
        // archive, where the round trip per entry, not the write itself, was the cost.
        final List<UUID> logActionIds = new ArrayList<>(plan.logs().size());
        final List<LocalDate> logDates = new ArrayList<>(plan.logs().size());
        final List<Integer> logCounts = new ArrayList<>(plan.logs().size());
        for (final LogDraft draft : plan.logs()) {
            // Never absent: the parser refuses a log whose action is not one of the plan's own, so every name here was just inserted above.
            logActionIds.add(Objects.requireNonNull(actionIds.get(draft.actionName()), "imported log names an action the plan does not hold"));
            logDates.add(draft.date());
            logCounts.add(draft.count());
        }
        ActionLog.setCounts(statements, user.id, logActionIds, logDates, logCounts);

        final Map<LocalDate, String> notes = new LinkedHashMap<>();
        for (final NoteDraft draft : plan.notes()) {
            notes.put(draft.date(), draft.content());
        }
        noteService.replaceAll(user, notes);
    }

    /**
     * The English wording for a refused archive or a refused row within one, for the API's {@code 400} body. The web surface instead resolves a
     * translated sentence via {@code partials/import-reason.html} (or, for {@link ImportReason.InvalidTextField}, the shared
     * {@code partials/text-failure-message.html}).
     *
     * @param reason the refusal cause
     * @return the default (English) message
     */
    // One exhaustive arm per ImportReason variant, so its length/coupling is the size of the catalogue rather than complexity - splitting it would
    // need a second switch over the same sealed type, which must either carry an unreachable `default -> throw` (a mutant no test can kill, and
    // PITest is held at 100%) or a reachable one that silently absorbs the next variant added. The flat table is the safer form (see
    // SubjectStatsExtensions.tile for the identical precedent).
    @SuppressWarnings({"OverlyLongMethod", "OverlyCoupledMethod"})
    @NotUiFacing(reason = "the /api/v1 import-rejection body; the Settings panel renders partials/import-reason.html instead")
    public static String message(final ImportReason reason) {
        return switch (reason) {
            case final ImportReason.NotZipArchive _ -> "The uploaded file is not a ZIP archive.";
            case final ImportReason.TooManyEntries tooMany -> "The uploaded archive holds more than " + tooMany.maxEntries() + " entries.";
            case final ImportReason.ArchiveTooLarge _ -> "The uploaded archive is too large once decompressed.";
            case final ImportReason.ArchiveUnreadable unreadable -> "The uploaded archive could not be read: " + unreadable.detail();
            case final ImportReason.CsvUnreadable _ ->
                "The file could not be read - a quoted value is never closed - check for an unbalanced \" character.";
            case final ImportReason.MissingMember missing -> "The archive does not contain " + missing.file() + ".";
            case final ImportReason.EmptyFile empty ->
                "The file is empty - it must start with the header row " + String.join(",", empty.columns()) + ".";
            case final ImportReason.WrongHeader wrongHeader -> "The header row must be exactly " + String.join(",", wrongHeader.columns()) + ".";
            case final ImportReason.WrongColumnCount wrongCount ->
                "Expected " + wrongCount.expected() + " columns but found " + wrongCount.actual() + ".";
            case final ImportReason.InvalidTextField invalid -> TextOutcomeExtensions.message(invalid.failure());
            case final ImportReason.InvalidColour _ -> "The colour must be a hex value such as #6366f1.";
            case final ImportReason.DuplicateAction duplicate -> "The action '" + duplicate.name() + "' appears more than once.";
            case final ImportReason.FutureLog futureLog -> "A log cannot be dated in the future (" + futureLog.date() + ").";
            case final ImportReason.UnknownAction unknown ->
                "No action named '" + unknown.actionName() + "' is defined in " + TransferFiles.ACTIONS_FILE + ".";
            case final ImportReason.NonNumericCount nonNumeric -> "'" + nonNumeric.raw() + "' is not a whole number.";
            case final ImportReason.CountOutOfRange outOfRange -> "The count must be between 1 and " + outOfRange.max() + ".";
            case final ImportReason.DuplicateLog duplicate ->
                "There is already a log for '" + duplicate.actionName() + "' on " + duplicate.date() + ".";
            case final ImportReason.EmptyNote emptyNote -> "The note for " + emptyNote.date() + " is empty - delete the row instead.";
            case final ImportReason.DuplicateNote duplicate -> "There is already a note for " + duplicate.date() + ".";
            case final ImportReason.InvalidDate invalidDate -> "'" + invalidDate.raw() + "' is not a date in YYYY-MM-DD form.";
        };
    }
}
