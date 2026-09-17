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
import java.util.function.Consumer;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.http.NotUiFacing;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.note.AttachmentPolicy;
import net.zodac.diurnal.note.Note;
import net.zodac.diurnal.note.NoteAttachment;
import net.zodac.diurnal.note.NoteAttachmentService;
import net.zodac.diurnal.note.NoteField;
import net.zodac.diurnal.note.NoteService;
import net.zodac.diurnal.persistence.LogStatements;
import net.zodac.diurnal.stats.cache.SubjectStatsCache;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of the data import, shared by the web UI's HTMX endpoints ({@code TransferInternalResource}) and the public REST API
 * ({@code TransferApiResource}), so a rule added or changed here applies to both surfaces by construction. The resources only translate the returned
 * {@link ImportResult} into their medium.
 *
 * <p>
 * <strong>An import REPLACES.</strong> Every action, day count, note and note attachment the account holds is removed, and the archive's contents
 * are written in their place - the account ends up holding exactly what the file describes, and nothing else. That is what makes the archive a
 * backup that can actually be restored, and it is also why the operation is worth confirming: {@link #preview(User, byte[])} runs the identical read
 * and validation and stops short of the write, so the confirmation is shown real figures from the real file rather than an estimate.
 *
 * <p>
 * The preview deliberately keeps <strong>no server-side state</strong> - the browser simply sends the same file again to confirm. Staging a parsed
 * archive between two requests would mean holding one user's whole journal, in the clear, in memory or in a table, for as long as they left the tab
 * open; re-reading the upload costs a few milliseconds and holds nothing. It also means the committed import validates the bytes it is about to
 * write, rather than trusting a verdict reached on an earlier request.
 *
 * <p>
 * Writes go through each package's own owner: {@link Note} content is written by {@link NoteService#replaceAll(User, Map)} and attachments by
 * {@code NoteAttachmentService.replaceAll}, which are the only things that can seal either, and the bulk deletes are the same entity statements
 * {@code AdminUserService} uses to clear an account. Actions are inserted
 * before their logs and flushed, because a log names its action by NAME and the id it needs does not exist until the action row does.
 *
 * <p>
 * <strong>The account's SETTINGS are the one part an import does not replace wholesale.</strong> They are written last, and only where the archive
 * names them: a preference always has a value, so a key the file leaves out cannot be asking for one to be removed, and {@code settings.csv} is
 * optional precisely so that an archive taken before it existed still restores without resetting the account's language. See {@link SettingsDraft}.
 *
 * <p>
 * The caller owns the transaction: {@link #apply(User, byte[])} must be invoked from a {@code @Transactional} endpoint, so a rejection part-way
 * through rolls back everything the deletes had already done. {@link #preview(User, byte[])} writes nothing and needs none.
 */
@ApplicationScoped
public class ImportService {

    private static final Logger LOGGER = LogManager.getLogger(ImportService.class);

    private final AttachmentPolicy attachmentPolicy;
    private final AppClock clock;
    private final NoteAttachmentService noteAttachmentService;
    private final NoteField noteField;
    private final NoteService noteService;
    private final LogStatements statements;
    private final TransferConfig transferConfig;

    /**
     * Injects the shared notes service, which owns every note write, the shared attachment service, the configured note field and extension policy,
     * the application clock, and the database's native statements.
     *
     * @param attachmentPolicy      the configured extension policy every imported attachment row is validated against
     * @param clock                 the application clock for date-boundary logic
     * @param noteAttachmentService the shared attachment service, which owns every attachment write
     * @param noteField             the configured day-note field every imported note row is validated against
     * @param noteService           the shared notes service
     * @param statements            the native action-log statements for the configured database
     * @param transferConfig        the archive settings, read for how large an uploaded archive may decompress to
     */
    @Inject
    public ImportService(final AttachmentPolicy attachmentPolicy, final AppClock clock, final NoteAttachmentService noteAttachmentService,
        final NoteField noteField, final NoteService noteService, final LogStatements statements, final TransferConfig transferConfig) {
        this.attachmentPolicy = attachmentPolicy;
        this.clock = clock;
        this.noteAttachmentService = noteAttachmentService;
        this.noteField = noteField;
        this.noteService = noteService;
        this.statements = statements;
        this.transferConfig = transferConfig;
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
        return switch (TransferArchive.unpack(archive, transferConfig.maxArchiveSizeBytes())) {
            case final ArchiveOutcome.Malformed malformed -> {
                LOGGER.debug("Import archive refused for user {}: {}", user.email, malformed.reason());
                yield new ImportResult.Malformed(malformed.reason());
            }
            case final ArchiveOutcome.Unpacked unpacked -> validate(user, unpacked, commit);
        };
    }

    private ImportResult validate(final User user, final ArchiveOutcome.Unpacked unpacked, final boolean commit) {
        // Resolved once for the whole file, in the user's own timezone - the same day boundary a single log write is judged against.
        final LocalDate today = clock.today(clock.zoneFor(user.timezone));

        return switch (ImportParser.parse(unpacked, today, noteField.field(), attachmentPolicy)) {
            case final ParseOutcome.Rejected rejected -> {
                LOGGER.warn("Import rejected for user {}: {} problem(s)", user.email, rejected.totalFound());
                yield new ImportResult.Rejected(rejected.problems(), rejected.totalFound());
            }
            case final ParseOutcome.Planned planned -> commitOrPreview(user, planned.plan(), commit);
        };
    }

    private ImportResult commitOrPreview(final User user, final ImportPlan plan, final boolean commit) {
        final ImportSummary summary = new ImportSummary(
            plan.actions().size(), plan.logs().size(), plan.notes().size(), plan.attachments().size(), plan.settings() != null,
            Math.toIntExact(Action.count("userId", user.id)),
            Math.toIntExact(ActionLog.count("userId", user.id)),
            Math.toIntExact(Note.count("userId", user.id)),
            Math.toIntExact(NoteAttachment.count("userId", user.id)));

        if (!commit) {
            return new ImportResult.Previewed(summary);
        }

        write(user, plan);
        // The COUNTS only - never an action name, and never a note's content.
        LOGGER.info("Data imported for user {}: {} action(s), {} log(s), {} note(s), {} attachment(s), settings={}, replacing {}/{}/{}/{}",
            user.email, summary.actions(), summary.logs(), summary.notes(), summary.attachments(), summary.settings(),
            summary.replacedActions(), summary.replacedLogs(), summary.replacedNotes(), summary.replacedAttachments());
        return new ImportResult.Applied(summary);
    }

    private void write(final User user, final ImportPlan plan) {
        // Logs before actions: a log has no meaning once its action is gone, and this is the order an account is cleared in elsewhere.
        ActionLog.deleteByUser(user.id);
        Action.delete("userId", user.id);

        final Map<String, UUID> actionIds = writeActions(user, plan.actions());

        // The log write below is a native statement, which does not see anything still sitting in the persistence context - so the action rows have
        // to actually be in the database before a log can reference one.
        Panache.getEntityManager().flush();
        writeLogs(user, plan.logs(), actionIds);

        final Map<LocalDate, String> notes = new LinkedHashMap<>();
        for (final NoteDraft draft : plan.notes()) {
            notes.put(draft.date(), draft.content());
        }
        noteService.replaceAll(user, notes);

        // AFTER the notes, and not before: replacing a journal takes its attachments with it (an attachment is embedded IN the writing), so files
        // written first would be deleted by the very next statement.
        writeAttachments(user, plan.attachments());

        final @Nullable SettingsDraft settings = plan.settings();
        if (settings != null) {
            writeSettings(user, settings);
        }
    }

    // Returns the id each name was inserted under, which is what lets the log write below name its action without a lookup per entry.
    private static Map<String, UUID> writeActions(final User user, final List<ActionDraft> drafts) {
        final Map<String, UUID> actionIds = new HashMap<>();
        for (final ActionDraft draft : drafts) {
            final Action action = new Action();
            action.userId = user.id;
            action.name = draft.name();
            action.colour = draft.colour();
            action.persist();
            actionIds.put(draft.name(), action.id);
        }
        return actionIds;
    }

    // Gathered into parallel lists and written in ONE statement rather than one per entry: a replaced history is ~33,000 entries for a 3-year
    // archive, where the round trip per entry, not the write itself, was the cost.
    private void writeLogs(final User user, final List<LogDraft> drafts, final Map<String, UUID> actionIds) {
        final List<UUID> logActionIds = new ArrayList<>(drafts.size());
        final List<LocalDate> logDates = new ArrayList<>(drafts.size());
        final List<Integer> logCounts = new ArrayList<>(drafts.size());
        for (final LogDraft draft : drafts) {
            // Never absent: the parser refuses a log whose action is not one of the plan's own, so every name here was just inserted above.
            logActionIds.add(Objects.requireNonNull(actionIds.get(draft.actionName()), "imported log names an action the plan does not hold"));
            logDates.add(draft.date());
            logCounts.add(draft.count());
        }
        ActionLog.setCounts(statements, user.id, logActionIds, logDates, logCounts);
        SubjectStatsCache.invalidate(user.id);
    }

    private void writeAttachments(final User user, final List<AttachmentDraft> drafts) {
        final List<NoteAttachmentService.AttachmentFile> attachments = new ArrayList<>(drafts.size());
        for (final AttachmentDraft draft : drafts) {
            attachments.add(new NoteAttachmentService.AttachmentFile(draft.date(), draft.name(), draft.fileName(), draft.file()));
        }
        noteAttachmentService.replaceAll(user, attachments);
    }

    // The settings the archive described, straight onto the entity. Unlike the four collections above there is nothing to delete first - a
    // preference is replaced in place, never removed - so this is an assignment per value the file named and nothing for the ones it did not.
    //
    // It does NOT go back through ProfileService, and that is the same division of labour every other member already has: the RULES are shared (the
    // parse put every value here through the identical validator that bean calls, which is what SettingsParser exists to do), and the writing is the
    // importer's, exactly as an imported action is written with Action.persist rather than through ActionService. Routing an already-validated value
    // back through a validator that reports by RETURNING a rejection would also add an outcome this path has no way to reach and no way to test.
    private static void writeSettings(final User user, final SettingsDraft settings) {
        assignIfNamed(settings.calendarView(), value -> user.calendarView = value);
        assignIfNamed(settings.decimalPlaces(), value -> user.decimalPlaces = value);
        assignIfNamed(settings.displayName(), value -> user.displayName = value);
        assignIfNamed(settings.font(), value -> user.font = value);
        assignIfNamed(settings.language(), value -> user.language = value);
        assignIfNamed(settings.noteColour(), value -> user.noteColour = value);
        assignIfNamed(settings.pageSize(), value -> user.pageSize = value);
        assignIfNamed(settings.showNoteCounter(), value -> user.showNoteCounter = value);
        assignIfNamed(settings.showStatsSummary(), value -> user.showStatsSummary = value);
        assignIfNamed(settings.theme(), value -> user.theme = value);
        // Blank is the explicit reset these two alone have, and is stored as the NULL that "follow the server default"/"follow the account's
        // language" already has exactly one representation as.
        assignIfNamed(settings.timezone(), value -> user.timezone = value.isEmpty() ? null : value); // NOPMD: NullAssignment
        assignIfNamed(settings.weekStart(), value -> user.weekStart = value.isEmpty() ? null : value); // NOPMD: NullAssignment

        // Assigned unconditionally where the values above are not: whenever the member is present these two are the COMPLETE set, so null is "no
        // overrides"/"never customised" rather than "the file did not say". See SettingsDraft.
        user.pageSizes = settings.pageSizes();
        user.statsFields = settings.statsFields();
        user.persist();
    }

    // Written as one helper rather than a run of `if (x != null)` statements: a dozen of those in a row is an NPath the linters refuse, and the
    // shape states the draft's contract (null is "the file did not describe this") once instead of a dozen times.
    private static <T> void assignIfNamed(final @Nullable T value, final Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * The English wording for a refused archive or a refused row within one, for the API's {@code 400} body. The web surface instead resolves a
     * translated sentence via {@code partials/import-reason.html} (or, for {@link ImportReason.InvalidTextField}, the shared
     * {@code partials/text-failure-message.html}).
     *
     * @param reason the refusal cause
     * @return the default (English) message
     */
    @NotUiFacing(reason = "the /api/v1 import-rejection body; the Settings panel renders partials/import-reason.html instead")
    public static String message(final ImportReason reason) {
        return reason.message();
    }
}
