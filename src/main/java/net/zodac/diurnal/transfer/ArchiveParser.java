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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.zodac.diurnal.colour.Colours;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.log.LogGuards;
import net.zodac.diurnal.text.TextField;
import net.zodac.diurnal.text.TextFieldExtensions;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextValidation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * One archive being read into an {@link ImportPlan}. Reached only through {@link ImportParser#parse(Map, LocalDate, TextField)}, which is where the
 * validation rules and the reasons behind them are documented.
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
    private final LocalDate today;
    private final TextField noteField;
    private final List<ImportProblem> reported = new ArrayList<>();

    private int total;

    /**
     * Prepares a parse of one unpacked archive.
     *
     * @param members   the unpacked archive members, keyed by file name
     * @param today     the acting user's current date, against which a log's future-date rule is applied
     * @param noteField the configured day-note field, whose length bound every note row must satisfy
     */
    ArchiveParser(final Map<String, String> members, final LocalDate today, final TextField noteField) {
        this.members = members;
        this.today = today;
        this.noteField = noteField;
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
        // An import is all-or-nothing: a single refused log or note row rejects the whole archive, so this check cannot move above the parse.
        if (anyProblems()) {
            return rejected();
        }
        return new ParseOutcome.Planned(new ImportPlan(actions, logs, notes));
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
        final String joinedHeader = String.join(",", header);
        if (rows.isEmpty()) {
            addProblem(file, HEADER_LINE, new ImportReason.EmptyFile(joinedHeader));
            return Optional.empty();
        }

        final CsvRow headerRow = rows.removeFirst();
        if (!matchesHeader(headerRow.fields(), header)) {
            addProblem(file, headerRow.line(), new ImportReason.WrongHeader(joinedHeader));
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

    private ParseOutcome.Rejected rejected() {
        return new ParseOutcome.Rejected(List.copyOf(reported), total);
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
