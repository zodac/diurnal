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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.zodac.diurnal.colour.Colours;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.log.LogGuards;
import net.zodac.diurnal.note.AttachmentPolicy;
import net.zodac.diurnal.text.TextField;
import net.zodac.diurnal.text.TextFieldExtensions;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextValidation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * One archive being read into an {@link ImportPlan}. Reached only through
 * {@link ImportParser#parse(ArchiveOutcome.Unpacked, LocalDate, TextField, AttachmentPolicy)}, which is where the validation rules and the reasons
 * behind them are documented.
 *
 * <p>
 * The problems found are the state that makes this an object rather than a chain of static calls: every step reports into the same list through
 * {@code addProblem}, so no step carries an accumulator in its signature or hands one back to be merged. Sharing one list is also what keeps the
 * report cap meaningful ({@link ImportParser#MAX_REPORTED_PROBLEMS}) - a list per step would be unbounded until the merge, on exactly the malformed
 * file the cap exists for. An instance lives for one {@link #parse()} call.
 */
final class ArchiveParser {

    private static final Logger LOGGER = LogManager.getLogger(ArchiveParser.class);

    private static final String ARCHIVE = "archive";
    private static final int NO_LINE = 0;
    private static final int HEADER_LINE = 1;

    private final Map<String, String> members;
    private final Map<String, byte[]> files;
    private final LocalDate today;
    private final TextField noteField;
    private final AttachmentPolicy attachmentPolicy;
    private final List<ImportProblem> reported = new ArrayList<>();

    private int total;

    /**
     * Prepares a parse of one unpacked archive.
     *
     * @param unpacked         the unpacked archive - its members and the attachment bytes it carried
     * @param today            the acting user's current date, against which a log's future-date rule is applied
     * @param noteField        the configured day-note field, whose length bound every note row must satisfy
     * @param attachmentPolicy the configured extension policy, which every attachment row must satisfy
     */
    ArchiveParser(final ArchiveOutcome.Unpacked unpacked, final LocalDate today, final TextField noteField,
        final AttachmentPolicy attachmentPolicy) {
        members = Map.copyOf(unpacked.members());
        files = Map.copyOf(unpacked.files());
        this.today = today;
        this.noteField = noteField;
        this.attachmentPolicy = attachmentPolicy;
    }

    /**
     * Reads and validates the whole archive.
     *
     * @return the validated plan, or the reasons it was refused
     */
    ParseOutcome parse() {
        for (final String required : TransferFiles.ALL_FILES) {
            if (!members.containsKey(required)) {
                addProblem(ARCHIVE, NO_LINE, new ImportReason.MissingMember(required));
            }
        }
        // Without every member there is nothing to validate against - a log's action is resolved from actions.csv - so this is a full stop rather
        // than one more problem in the list.
        if (anyProblems()) {
            return rejected();
        }

        final Optional<List<CsvRow>> actionRows = dataRows(TransferFiles.ACTIONS_FILE, TransferFiles.ACTIONS_HEADER);
        final Optional<List<CsvRow>> logRows = dataRows(TransferFiles.LOGS_FILE, TransferFiles.LOGS_HEADER);
        final Optional<List<CsvRow>> noteRows = dataRows(TransferFiles.NOTES_FILE, TransferFiles.NOTES_HEADER);
        if (actionRows.isEmpty() || logRows.isEmpty() || noteRows.isEmpty()) {
            return rejected();
        }

        final List<ActionDraft> actions = parseActions(actionRows.get());
        final Set<String> actionNames = new HashSet<>();
        for (final ActionDraft action : actions) {
            actionNames.add(action.name());
        }

        // A refused action row would make every log naming it report a second, misleading "no such action" problem, so a bad actions.csv stops here.
        if (anyProblems()) {
            return rejected();
        }

        final List<LogDraft> logs = parseLogs(logRows.get(), actionNames);
        final List<NoteDraft> notes = parseNotes(noteRows.get());
        final List<AttachmentDraft> attachments = parseAttachments();
        final @Nullable SettingsDraft settings = parseSettings();
        // An import is all-or-nothing: a single refused log, note, attachment or settings row rejects the whole archive, so this check cannot move
        // above the parse.
        if (anyProblems()) {
            return rejected();
        }
        return new ParseOutcome.Planned(new ImportPlan(actions, logs, notes, attachments, settings));
    }

    private Optional<List<CsvRow>> dataRows(final String file, final List<String> header) {
        return switch (Csv.parse(members.getOrDefault(file, ""))) {
            case final CsvOutcome.Malformed malformed -> {
                addProblem(file, malformed.line(), new ImportReason.CsvUnreadable());
                yield Optional.empty();
            }
            case final CsvOutcome.Parsed parsed -> headerAndDataRows(file, header, parsed.rows());
        };
    }

    private Optional<List<CsvRow>> headerAndDataRows(final String file, final List<String> header, final List<CsvRow> parsedRows) {
        final List<CsvRow> rows = new ArrayList<>(parsedRows);
        if (rows.isEmpty()) {
            addProblem(file, HEADER_LINE, new ImportReason.EmptyFile(header));
            return Optional.empty();
        }

        final CsvRow headerRow = rows.removeFirst();
        if (!matchesHeader(headerRow.fields(), header)) {
            addProblem(file, headerRow.line(), new ImportReason.WrongHeader(header));
            return Optional.empty();
        }

        final List<CsvRow> dataRows = new ArrayList<>();
        for (final CsvRow row : rows) {
            if (isBlank(row)) {
                continue;
            }
            if (row.fields().size() != header.size()) {
                addProblem(file, row.line(), new ImportReason.WrongColumnCount(header.size(), row.fields().size()));
                continue;
            }
            dataRows.add(row);
        }

        return Optional.of(dataRows);
    }

    private List<ActionDraft> parseActions(final List<CsvRow> rows) {
        final List<ActionDraft> actions = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        for (final CsvRow row : rows) {
            final TextOutcome nameOutcome = TextValidation.check(TextFields.ACTION_NAME, row.fields().getFirst());
            if (!(nameOutcome instanceof TextOutcome.Valid(final String value))) {
                addProblem(TransferFiles.ACTIONS_FILE, row.line(), new ImportReason.InvalidTextField((TextOutcome.Failure) nameOutcome));
                continue;
            }

            final String colour = row.fields().get(1).strip();
            if (Colours.isInvalidHex(colour)) {
                addProblem(TransferFiles.ACTIONS_FILE, row.line(), new ImportReason.InvalidColour());
                continue;
            }
            if (!seen.add(value)) {
                addProblem(TransferFiles.ACTIONS_FILE, row.line(), new ImportReason.DuplicateAction(value));
                continue;
            }
            actions.add(new ActionDraft(value, colour));
        }
        return actions;
    }

    private List<LogDraft> parseLogs(final List<CsvRow> rows, final Set<String> actionNames) {
        final List<LogDraft> logs = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        for (final CsvRow row : rows) {
            final @Nullable LocalDate date = parseDate(TransferFiles.LOGS_FILE, row);
            if (date == null) {
                continue;
            }
            if (LogGuards.isFuture(date, today)) {
                addProblem(TransferFiles.LOGS_FILE, row.line(), new ImportReason.FutureLog(date));
                continue;
            }

            // Normalised with the same pass the name itself went through, so a log written against "  Reading " still finds the action stored as
            // "Reading" rather than being refused for a difference the user cannot see.
            final String actionName = TextFieldExtensions.normalise(TextFields.ACTION_NAME, row.fields().get(1));
            if (!actionNames.contains(actionName)) {
                addProblem(TransferFiles.LOGS_FILE, row.line(), new ImportReason.UnknownAction(actionName));
                continue;
            }

            // Parsed inline rather than in a helper: as a helper, its rejection had nothing observable to return - the whole archive is refused
            // either way - which left the branch impossible to pin down in a test.
            final String rawCount = row.fields().get(2).strip();
            final int count;
            try {
                count = Integer.parseInt(rawCount);
            } catch (final NumberFormatException e) {
                LOGGER.trace("{} line {}: the count column did not parse as a whole number", TransferFiles.LOGS_FILE, row.line(), e);
                addProblem(TransferFiles.LOGS_FILE, row.line(), new ImportReason.NonNumericCount(rawCount));
                continue;
            }
            // Rejected, never clamped: a file of ten thousand rows cannot afford a silent correction nobody is watching.
            if (count < 1 || count > ActionLog.MAX_DAILY_COUNT) {
                addProblem(TransferFiles.LOGS_FILE, row.line(), new ImportReason.CountOutOfRange(ActionLog.MAX_DAILY_COUNT));
                continue;
            }
            if (!seen.add(date + " " + actionName)) {
                addProblem(TransferFiles.LOGS_FILE, row.line(), new ImportReason.DuplicateLog(actionName, date));
                continue;
            }
            logs.add(new LogDraft(date, actionName, count));
        }
        return logs;
    }

    private List<NoteDraft> parseNotes(final List<CsvRow> rows) {
        final List<NoteDraft> notes = new ArrayList<>();
        final Set<LocalDate> seen = new HashSet<>();

        for (final CsvRow row : rows) {
            final @Nullable LocalDate date = parseDate(TransferFiles.NOTES_FILE, row);
            if (date == null) {
                continue;
            }

            // A note is deliberately NOT future-checked - unlike a log, writing down a day in advance is a legitimate thing to do.
            final TextOutcome contentOutcome = TextValidation.check(noteField, row.fields().get(1));
            if (!(contentOutcome instanceof TextOutcome.Valid(final String value))) {
                // The pipeline's own reason, worded from the field and never quoting the value - so no note content reaches this banner.
                addProblem(TransferFiles.NOTES_FILE, row.line(), new ImportReason.InvalidTextField((TextOutcome.Failure) contentOutcome));
                continue;
            }
            if (value.isEmpty()) {
                addProblem(TransferFiles.NOTES_FILE, row.line(), new ImportReason.EmptyNote(date));
                continue;
            }
            if (!seen.add(date)) {
                addProblem(TransferFiles.NOTES_FILE, row.line(), new ImportReason.DuplicateNote(date));
                continue;
            }
            notes.add(new NoteDraft(date, value));
        }
        return notes;
    }

    // attachments.csv is OPTIONAL, unlike the three members above: an archive exported before attachments existed is a complete export of what the
    // account held then, and reading it as "no attachments" is exactly right under replace-all. See TransferFiles.ALL_MEMBERS.
    private List<AttachmentDraft> parseAttachments() {
        if (!members.containsKey(TransferFiles.ATTACHMENTS_FILE)) {
            return List.of();
        }

        final Optional<List<CsvRow>> rows = dataRows(TransferFiles.ATTACHMENTS_FILE, TransferFiles.ATTACHMENTS_HEADER);
        if (rows.isEmpty()) {
            return List.of();
        }

        final List<AttachmentDraft> attachments = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        for (final CsvRow row : rows.get()) {
            final @Nullable AttachmentDraft draft = attachmentDraft(row, seen);
            if (draft != null) {
                attachments.add(draft);
            }
        }
        return attachments;
    }

    // One manifest row, or null once the reason it cannot be one has been reported.
    private @Nullable AttachmentDraft attachmentDraft(final CsvRow row, final Set<String> seen) {
        final @Nullable LocalDate date = parseDate(TransferFiles.ATTACHMENTS_FILE, row);
        if (date == null) {
            return null;
        }

        // The same field a rename submits, so an imported name meets the rules a typed one does - including the square brackets the note's own embed
        // token is written with, which a name carrying one would break. BOTH names are checked against it: both end up sealed in the same row, and
        // an archive is not a trusted source just because this application wrote the last one.
        final TextOutcome nameOutcome = TextValidation.check(TextFields.ATTACHMENT_NAME, row.fields().get(1));
        if (!(nameOutcome instanceof TextOutcome.Valid(final String name))) {
            addProblem(TransferFiles.ATTACHMENTS_FILE, row.line(), new ImportReason.InvalidTextField((TextOutcome.Failure) nameOutcome));
            return null;
        }
        final TextOutcome fileNameOutcome = TextValidation.check(TextFields.ATTACHMENT_NAME, row.fields().get(2));
        if (!(fileNameOutcome instanceof TextOutcome.Valid(final String fileName))) {
            addProblem(TransferFiles.ATTACHMENTS_FILE, row.line(), new ImportReason.InvalidTextField((TextOutcome.Failure) fileNameOutcome));
            return null;
        }
        // The deployment's extension whitelist is about what a file IS, so it is the FILE name that has to satisfy it - the same value an upload is
        // judged on (NoteAttachmentService.attach), and the one a rename cannot launder.
        if (!attachmentPolicy.accepts(fileName)) {
            addProblem(TransferFiles.ATTACHMENTS_FILE, row.line(), new ImportReason.AttachmentTypeNotAllowed(fileName, attachmentPolicy.accepted()));
            return null;
        }

        final String entry = row.fields().get(3).strip();
        final byte @Nullable [] file = files.get(entry);
        if (file == null) {
            addProblem(TransferFiles.ATTACHMENTS_FILE, row.line(), new ImportReason.MissingAttachmentFile(entry));
            return null;
        }
        if (file.length == 0) {
            addProblem(TransferFiles.ATTACHMENTS_FILE, row.line(), new ImportReason.EmptyAttachment(entry));
            return null;
        }
        // A note embeds a file BY name, so two of one name on a day would leave its token naming both.
        if (!seen.add(date + " " + name)) {
            addProblem(TransferFiles.ATTACHMENTS_FILE, row.line(), new ImportReason.DuplicateAttachment(name, date));
            return null;
        }
        return new AttachmentDraft(date, name, fileName, file);
    }

    // settings.csv is OPTIONAL, like attachments.csv - but its absence says something different. An account ALWAYS has settings, so there is no
    // "this account has none" for a missing member to mean; the only other reading, resetting every preference to its default, would change the
    // language out from under someone restoring a backup taken before this member existed. Absent therefore means "this file is silent", and the
    // stored settings are left alone. See TransferFiles.ALL_MEMBERS.
    private @Nullable SettingsDraft parseSettings() {
        if (!members.containsKey(TransferFiles.SETTINGS_FILE)) {
            return null;
        }

        // Reported through this parser's own list, so a broken settings member is counted, located and capped alongside every other member's rows.
        return dataRows(TransferFiles.SETTINGS_FILE, TransferFiles.SETTINGS_HEADER)
                .map(rows -> new SettingsParser(rows, (line, reason) -> addProblem(TransferFiles.SETTINGS_FILE, line, reason)).parse())
                .orElse(null);
    }

    private @Nullable LocalDate parseDate(final String file, final CsvRow row) {
        final String raw = row.fields().getFirst().strip();
        try {
            return LocalDate.parse(raw);
        } catch (final DateTimeParseException e) {
            LOGGER.trace("{} line {}: the date column did not parse as YYYY-MM-DD", file, row.line(), e);
            addProblem(file, row.line(), new ImportReason.InvalidDate(raw));
            return null;
        }
    }

    private void addProblem(final String file, final int line, final ImportReason reason) {
        total++;
        if (reported.size() < ImportParser.MAX_REPORTED_PROBLEMS) {
            reported.add(new ImportProblem(file, line, reason));
        }
    }

    private boolean anyProblems() {
        return total > 0;
    }

    // Sorted so the report reads top-to-bottom through the archive - member by member, then line by line - however the rules inside one member
    // happened to be applied. settings.csv is the member that needs it: it is a key/value file, so its rules are applied per SETTING rather than
    // per row, and a list that jumped about inside a file is a list nobody can work down. The sort is over the REPORTED problems, which
    // MAX_REPORTED_PROBLEMS has already bounded, and is stable - so two problems sharing a line stay in the order they were found.
    private ParseOutcome.Rejected rejected() {
        final List<ImportProblem> ordered = new ArrayList<>(reported);
        ordered.sort(Comparator
            .comparingInt((final ImportProblem problem) -> TransferFiles.ALL_MEMBERS.indexOf(problem.file()))
            .thenComparingInt(ImportProblem::line));
        return new ParseOutcome.Rejected(List.copyOf(ordered), total);
    }

    private static boolean matchesHeader(final List<String> actual, final List<String> expected) {
        final int columnCount = expected.size();
        if (actual.size() != columnCount) {
            return false;
        }
        for (int i = 0; i < columnCount; i++) {
            if (!actual.get(i).strip().toLowerCase(Locale.ROOT).equals(expected.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(final CsvRow row) {
        for (final String field : row.fields()) {
            if (!field.isBlank()) {
                return false;
            }
        }
        return true;
    }
}
