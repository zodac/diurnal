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
import net.zodac.diurnal.text.TextFieldExtensions;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextOutcomeExtensions;
import net.zodac.diurnal.text.TextValidation;
import org.jspecify.annotations.Nullable;

/**
 * Reads an unpacked archive into a validated {@link ImportPlan}, or into the list of reasons it cannot be one - pure, with no persistence and no
 * request state.
 *
 * <p>
 * <strong>Every rule here is a rule that already existed.</strong> A name goes through {@link TextFields#ACTION_NAME}, a note through
 * {@link TextFields#NOTE}, a colour through {@link Colours#isInvalidHex(String)}, a count against {@link ActionLog#MAX_DAILY_COUNT}, and a log's date
 * through {@link LogGuards#isFuture(LocalDate, LocalDate)} - the same validators the forms and the API call. An import is a bulk version of writes
 * the user could have made one at a time, so it must not be a way to get values into the database that no other path would accept.
 *
 * <p>
 * <strong>Nothing is coerced.</strong> A count of {@code 1500} is refused rather than clamped to 999, a malformed colour is refused rather than
 * replaced with the default, and an over-long note is refused rather than truncated. Where an interactive form can afford to fix up a value the user
 * is watching it fix, a file of ten thousand rows cannot: silently altering one of them produces an import that succeeded and is wrong.
 *
 * <p>
 * <strong>An archive is accepted or refused as a whole.</strong> There is no "import the good rows" mode, because the import replaces everything the
 * account holds - so committing a partial file would delete the data that the refused rows were the replacement for.
 *
 * <p>
 * Problems carry the member and the line so they can actually be fixed, and are capped at {@link #MAX_REPORTED_PROBLEMS}: a file that has been saved
 * from the wrong tool generates a problem per row, and a screen of ten thousand identical complaints helps nobody. <strong>A reason never quotes note
 * content</strong> - see {@link ImportProblem}.
 */
public final class ImportParser {

    /**
     * The most problems reported back from one archive. Further problems are counted but not listed.
     */
    public static final int MAX_REPORTED_PROBLEMS = 50;

    private static final String ARCHIVE = "archive";
    private static final int NO_LINE = 0;
    private static final int HEADER_LINE = 1;

    private ImportParser() {

    }

    /**
     * Reads and validates a whole archive.
     *
     * @param members the unpacked archive members, keyed by file name
     * @param today   the acting user's current date, against which a log's future-date rule is applied
     * @return the validated plan, or the reasons it was refused
     */
    public static ParseOutcome parse(final Map<String, String> members, final LocalDate today) {
        final Problems problems = new Problems();

        for (final String required : TransferFiles.ALL_FILES) {
            if (!members.containsKey(required)) {
                problems.add(ARCHIVE, NO_LINE, "The archive does not contain " + required + ", which a complete export always has.");
            }
        }
        // Without every member there is nothing to validate against - a log's action is resolved from actions.csv - so this is a full stop rather
        // than one more problem in the list.
        if (problems.any()) {
            return problems.rejected();
        }

        final Optional<List<CsvRow>> actionRows = dataRows(members, TransferFiles.ACTIONS_FILE, TransferFiles.ACTIONS_HEADER, problems);
        final Optional<List<CsvRow>> logRows = dataRows(members, TransferFiles.LOGS_FILE, TransferFiles.LOGS_HEADER, problems);
        final Optional<List<CsvRow>> noteRows = dataRows(members, TransferFiles.NOTES_FILE, TransferFiles.NOTES_HEADER, problems);
        if (actionRows.isEmpty() || logRows.isEmpty() || noteRows.isEmpty()) {
            return problems.rejected();
        }

        final List<ActionDraft> actions = parseActions(actionRows.get(), problems);
        final Set<String> actionNames = new HashSet<>();
        for (final ActionDraft action : actions) {
            actionNames.add(action.name());
        }

        final List<LogDraft> logs = parseLogs(logRows.get(), actionNames, today, problems);
        final List<NoteDraft> notes = parseNotes(noteRows.get(), problems);

        if (problems.any()) {
            return problems.rejected();
        }
        return new ParseOutcome.Planned(new ImportPlan(actions, logs, notes));
    }

    private static Optional<List<CsvRow>> dataRows(final Map<String, String> members, final String file, final List<String> header,
        final Problems problems) {
        return switch (Csv.parse(members.getOrDefault(file, ""))) {
            case final CsvOutcome.Malformed malformed -> {
                problems.add(file, malformed.line(), "The file could not be read - " + malformed.reason() + ".");
                yield Optional.empty();
            }
            case final CsvOutcome.Parsed parsed -> headerAndDataRows(file, header, parsed.rows(), problems);
        };
    }

    private static Optional<List<CsvRow>> headerAndDataRows(final String file, final List<String> header, final List<CsvRow> parsedRows,
        final Problems problems) {
        final List<CsvRow> rows = new ArrayList<>(parsedRows);
        if (rows.isEmpty()) {
            problems.add(file, HEADER_LINE, "The file is empty - it must start with the header row " + String.join(",", header) + ".");
            return Optional.empty();
        }

        final CsvRow headerRow = rows.removeFirst();
        if (!matchesHeader(headerRow.fields(), header)) {
            problems.add(file, headerRow.line(), "The header row must be exactly " + String.join(",", header) + ".");
            return Optional.empty();
        }

        final List<CsvRow> dataRows = new ArrayList<>();
        for (final CsvRow row : rows) {
            if (isBlank(row)) {
                continue;
            }
            if (row.fields().size() != header.size()) {
                problems.add(file, row.line(), "Expected " + header.size() + " columns but found " + row.fields().size() + ".");
                continue;
            }
            dataRows.add(row);
        }

        return Optional.of(dataRows);
    }

    private static boolean matchesHeader(final List<String> actual, final List<String> expected) {
        if (actual.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
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

    private static List<ActionDraft> parseActions(final List<CsvRow> rows, final Problems problems) {
        final List<ActionDraft> actions = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        for (final CsvRow row : rows) {
            final TextOutcome nameOutcome = TextValidation.check(TextFields.ACTION_NAME, row.fields().getFirst());
            if (!(nameOutcome instanceof final TextOutcome.Valid validName)) {
                problems.add(TransferFiles.ACTIONS_FILE, row.line(), TextOutcomeExtensions.message((TextOutcome.Failure) nameOutcome));
                continue;
            }

            final String colour = row.fields().get(1).strip();
            if (Colours.isInvalidHex(colour)) {
                problems.add(TransferFiles.ACTIONS_FILE, row.line(), "The colour must be a hex value such as #6366f1.");
                continue;
            }
            if (!seen.add(validName.value())) {
                problems.add(TransferFiles.ACTIONS_FILE, row.line(), "The action '" + validName.value() + "' appears more than once.");
                continue;
            }
            actions.add(new ActionDraft(validName.value(), colour));
        }
        return actions;
    }

    private static List<LogDraft> parseLogs(final List<CsvRow> rows, final Set<String> actionNames, final LocalDate today,
        final Problems problems) {
        final List<LogDraft> logs = new ArrayList<>();
        final Set<String> seen = new HashSet<>();

        for (final CsvRow row : rows) {
            final @Nullable LocalDate date = parseDate(TransferFiles.LOGS_FILE, row, problems);
            if (date == null) {
                continue;
            }
            if (LogGuards.isFuture(date, today)) {
                problems.add(TransferFiles.LOGS_FILE, row.line(), "A log cannot be dated in the future (" + date + ").");
                continue;
            }

            // Normalised with the same pass the name itself went through, so a log written against "  Reading " still finds the action stored as
            // "Reading" rather than being refused for a difference the user cannot see.
            final String actionName = TextFieldExtensions.normalise(TextFields.ACTION_NAME, row.fields().get(1));
            if (!actionNames.contains(actionName)) {
                problems.add(TransferFiles.LOGS_FILE, row.line(),
                    "No action named '" + actionName + "' is defined in " + TransferFiles.ACTIONS_FILE + ".");
                continue;
            }

            // Parsed inline rather than in a helper: as a helper, its rejection had nothing observable to return - the whole archive is refused
            // either way - which left the branch impossible to pin down in a test.
            final String rawCount = row.fields().get(2).strip();
            final int count;
            try {
                count = Integer.parseInt(rawCount);
            } catch (final NumberFormatException e) {
                problems.add(TransferFiles.LOGS_FILE, row.line(), "'" + rawCount + "' is not a whole number.");
                continue;
            }
            // Rejected, never clamped: a file of ten thousand rows cannot afford a silent correction nobody is watching.
            if (count < 1 || count > ActionLog.MAX_DAILY_COUNT) {
                problems.add(TransferFiles.LOGS_FILE, row.line(), "The count must be between 1 and " + ActionLog.MAX_DAILY_COUNT + ".");
                continue;
            }
            if (!seen.add(date + " " + actionName)) {
                problems.add(TransferFiles.LOGS_FILE, row.line(), "There is already a log for '" + actionName + "' on " + date + ".");
                continue;
            }
            logs.add(new LogDraft(date, actionName, count));
        }
        return logs;
    }

    private static List<NoteDraft> parseNotes(final List<CsvRow> rows, final Problems problems) {
        final List<NoteDraft> notes = new ArrayList<>();
        final Set<LocalDate> seen = new HashSet<>();

        for (final CsvRow row : rows) {
            final @Nullable LocalDate date = parseDate(TransferFiles.NOTES_FILE, row, problems);
            if (date == null) {
                continue;
            }

            // A note is deliberately NOT future-checked - unlike a log, writing down a day in advance is a legitimate thing to do.
            final TextOutcome contentOutcome = TextValidation.check(TextFields.NOTE, row.fields().get(1));
            if (!(contentOutcome instanceof final TextOutcome.Valid validContent)) {
                // The pipeline's own message, which is worded from the field and never quotes the value - so no note content reaches this banner.
                problems.add(TransferFiles.NOTES_FILE, row.line(), TextOutcomeExtensions.message((TextOutcome.Failure) contentOutcome));
                continue;
            }
            if (validContent.value().isEmpty()) {
                problems.add(TransferFiles.NOTES_FILE, row.line(), "The note for " + date + " is empty - delete the row instead.");
                continue;
            }
            if (!seen.add(date)) {
                problems.add(TransferFiles.NOTES_FILE, row.line(), "There is already a note for " + date + ".");
                continue;
            }
            notes.add(new NoteDraft(date, validContent.value()));
        }
        return notes;
    }

    private static @Nullable LocalDate parseDate(final String file, final CsvRow row, final Problems problems) {
        final String raw = row.fields().getFirst().strip();
        try {
            return LocalDate.parse(raw);
        } catch (final DateTimeParseException e) {
            problems.add(file, row.line(), "'" + raw + "' is not a date in YYYY-MM-DD form.");
            return null;
        }
    }

    private static final class Problems {

        private final List<ImportProblem> reported = new ArrayList<>();
        private int total;

        private void add(final String file, final int line, final String reason) {
            total++;
            if (reported.size() < MAX_REPORTED_PROBLEMS) {
                reported.add(new ImportProblem(file, line, reason));
            }
        }

        private boolean any() {
            return total > 0;
        }

        private ParseOutcome.Rejected rejected() {
            return new ParseOutcome.Rejected(List.copyOf(reported), total);
        }
    }
}
