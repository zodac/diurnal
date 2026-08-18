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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.zodac.diurnal.text.TextField;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ImportParser}: that an archive is read into exactly the values a form would have stored, that every rule the interactive
 * surfaces apply is applied here too, and that a rejection is located precisely enough to be fixed.
 */
class ImportParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);

    // The note field at its default bound, standing in for what NoteField resolves from NOTE_MAX_LENGTH at runtime.
    // note_boundIsTheConfiguredOne_notTheDefault covers a deployment that has set its own.
    private static final TextField NOTE_FIELD = TextFields.DEFAULT_NOTE;

    private static final String ACTIONS = "name,colour\r\nRunning,#e11d48\r\nReading,#0ea5e9\r\n";
    private static final String LOGS = "date,action,count\r\n2026-08-01,Running,1\r\n";
    private static final String NOTES = "date,content\r\n2026-08-01,\"Long run today.\"\r\n";

    // The header-only forms of the two members, for the cases that are about something else entirely.
    private static final String NO_LOGS = "date,action,count\r\n";
    private static final String NO_NOTES = "date,content\r\n";

    @Test
    void parse_readsCompleteArchive() {
        final ParseOutcome outcome = ImportParser.parse(archive(ACTIONS, LOGS, NOTES), TODAY, NOTE_FIELD);

        final ImportPlan expected = new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")),
            List.of(new LogDraft(LocalDate.of(2026, 8, 1), "Running", 1)),
            List.of(new NoteDraft(LocalDate.of(2026, 8, 1), "Long run today.")));
        assertThat(outcome)
            .as("a well-formed archive should read into exactly the values it describes")
            .isEqualTo(new ParseOutcome.Planned(expected));
    }

    @Test
    void parse_acceptsFutureDatedNoteButNotFutureDatedLog() {
        final String futureNote = "date,content\r\n2027-01-01,\"a plan for the new year\"\r\n";

        final ImportPlan withFutureNote = new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")),
            List.of(new LogDraft(LocalDate.of(2026, 8, 1), "Running", 1)),
            List.of(new NoteDraft(LocalDate.of(2027, 1, 1), "a plan for the new year")));
        assertThat(ImportParser.parse(archive(ACTIONS, LOGS, futureNote), TODAY, NOTE_FIELD))
            .as("writing down a day in advance is legitimate, exactly as it is in the note box")
            .isEqualTo(new ParseOutcome.Planned(withFutureNote));

        assertThat(problems(ImportParser.parse(archive(ACTIONS, "date,action,count\r\n2027-01-01,Running,1\r\n", NOTES), TODAY, NOTE_FIELD)))
            .as("claiming to have already performed an action tomorrow is not")
            .containsExactly(new ImportProblem(TransferFiles.LOGS_FILE, 2, new ImportReason.FutureLog(LocalDate.of(2027, 1, 1))));
    }

    @Test
    void parse_requiresEveryMemberOfCompleteExport() {
        final Map<String, String> incomplete = new HashMap<>();
        incomplete.put(TransferFiles.ACTIONS_FILE, ACTIONS);

        final List<ImportProblem> expected = List.of(
            new ImportProblem("archive", 0, new ImportReason.MissingMember(TransferFiles.LOGS_FILE)),
            new ImportProblem("archive", 0, new ImportReason.MissingMember(TransferFiles.NOTES_FILE)));
        assertThat(problems(ImportParser.parse(incomplete, TODAY, NOTE_FIELD)))
            .as("an import replaces everything, so a partial archive would delete what its missing members describe")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void parse_requiresTheExactHeaderRow() {
        assertThat(problems(ImportParser.parse(archive("name,color\r\nRunning,#e11d48\r\n", LOGS, NOTES), TODAY, NOTE_FIELD)))
            .as("guessing at a renamed column would let a file that means one thing be imported as another")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 1, new ImportReason.WrongHeader("name,colour")));
    }

    @Test
    void parse_toleratesHeaderCasingAndPadding() {
        assertThat(ImportParser.parse(archive(" Name , COLOUR \r\nRunning,#e11d48\r\n", NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD))
            .as("a spreadsheet may capitalise a header; the names and their order are what must match")
            .isEqualTo(new ParseOutcome.Planned(new ImportPlan(List.of(new ActionDraft("Running", "#e11d48")), List.of(), List.of())));
    }

    @Test
    void parse_reportsAnEmptyMember() {
        final ImportProblem expected = new ImportProblem(TransferFiles.ACTIONS_FILE, 1, new ImportReason.EmptyFile("name,colour"));
        assertThat(problems(ImportParser.parse(archive("", LOGS, NOTES), TODAY, NOTE_FIELD)))
            .as("a member with no header at all is not a member of an export")
            .containsExactly(expected);
    }

    @Test
    void parse_reportsAnUnreadableMember() {
        final ImportProblem expected = new ImportProblem(TransferFiles.NOTES_FILE, 2, new ImportReason.CsvUnreadable());
        assertThat(problems(ImportParser.parse(archive(ACTIONS, LOGS, "date,content\r\n2026-08-01,\"never closed\r\n"), TODAY, NOTE_FIELD)))
            .as("a CSV that cannot be parsed is located at the record that broke it")
            .containsExactly(expected);
    }

    @Test
    void parse_reportsRowWithWrongNumberOfColumns() {
        assertThat(problems(ImportParser.parse(archive("name,colour\r\nRunning\r\n", LOGS, NOTES), TODAY, NOTE_FIELD)))
            .as("a short row is ambiguous rather than partially usable")
            .contains(new ImportProblem(TransferFiles.ACTIONS_FILE, 2, new ImportReason.WrongColumnCount(2, 1)));
    }

    @Test
    void parse_ignoresBlankRows() {
        assertThat(ImportParser.parse(archive("name,colour\r\n\r\nRunning,#e11d48\r\n\r\n", NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD))
            .as("a trailing blank line from an editor is noise, not a row that failed")
            .isEqualTo(new ParseOutcome.Planned(new ImportPlan(List.of(new ActionDraft("Running", "#e11d48")), List.of(), List.of())));
    }

    @Test
    void parse_appliesTheSharedActionNameRules() {
        final String blankAndTooLong = "name,colour\r\n,#e11d48\r\n" + "x".repeat(101) + ",#0ea5e9\r\n";

        assertThat(problems(ImportParser.parse(archive(blankAndTooLong, NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD)))
            .as("an import must not be a way to store a name no form would accept")
            .containsExactlyElementsOf(List.of(
                new ImportProblem(TransferFiles.ACTIONS_FILE, 2,
                    new ImportReason.InvalidTextField(new TextOutcome.Blank(TextFields.ACTION_NAME))),
                new ImportProblem(TransferFiles.ACTIONS_FILE, 3,
                    new ImportReason.InvalidTextField(new TextOutcome.TooLong(TextFields.ACTION_NAME)))));
    }

    @Test
    void parse_rejectsMalformedColourRatherThanDefaultingIt() {
        assertThat(problems(ImportParser.parse(archive("name,colour\r\nRunning,red\r\n", NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD)))
            .as("silently substituting the default would produce an import that succeeded and is wrong")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 2, new ImportReason.InvalidColour()));
    }

    @Test
    void parse_rejectsDuplicateActionName() {
        final String duplicated = "name,colour\r\nRunning,#e11d48\r\nRunning,#0ea5e9\r\n";

        assertThat(problems(ImportParser.parse(archive(duplicated, NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD)))
            .as("an action name is unique within an account, so a file cannot describe two")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 3, new ImportReason.DuplicateAction("Running")));
    }

    @Test
    void parse_rejectsLogNamingUndefinedAction() {
        final String unknown = "date,action,count\r\n2026-08-01,Swimming,1\r\n";

        assertThat(problems(ImportParser.parse(archive(ACTIONS, unknown, NOTES), TODAY, NOTE_FIELD)))
            .as("a log's action is resolved from the archive's own actions, so an unknown name has nothing to point at")
            .containsExactly(new ImportProblem(TransferFiles.LOGS_FILE, 2, new ImportReason.UnknownAction("Swimming")));
    }

    @Test
    void parse_matchesLogsActionOnTheNormalisedName() {
        final String padded = "date,action,count\r\n2026-08-01,  Running ,2\r\n";

        final ImportPlan expected = new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")),
            List.of(new LogDraft(LocalDate.of(2026, 8, 1), "Running", 2)),
            List.of());
        assertThat(ImportParser.parse(archive(ACTIONS, padded, NO_NOTES), TODAY, NOTE_FIELD))
            .as("a difference the user cannot see must not refuse their file")
            .isEqualTo(new ParseOutcome.Planned(expected));
    }

    @Test
    void parse_rejectsCountOutsideStoredRangeRatherThanClamping() {
        final String outOfRange = "date,action,count\r\n2026-08-01,Running,0\r\n2026-08-02,Running,1500\r\n2026-08-03,Running,many\r\n";

        final List<ImportProblem> expected = List.of(
            new ImportProblem(TransferFiles.LOGS_FILE, 2, new ImportReason.CountOutOfRange(999)),
            new ImportProblem(TransferFiles.LOGS_FILE, 3, new ImportReason.CountOutOfRange(999)),
            new ImportProblem(TransferFiles.LOGS_FILE, 4, new ImportReason.NonNumericCount("many")));
        assertThat(problems(ImportParser.parse(archive(ACTIONS, outOfRange, NOTES), TODAY, NOTE_FIELD)))
            .as("a file of ten thousand rows cannot afford a silent correction the user is not watching")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void parse_acceptsCountAtEachEndOfTheStoredRange() {
        final String bounds = "date,action,count\r\n2026-08-01,Running,1\r\n2026-08-02,Running,999\r\n";

        final ImportPlan expected = new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")),
            List.of(new LogDraft(LocalDate.of(2026, 8, 1), "Running", 1), new LogDraft(LocalDate.of(2026, 8, 2), "Running", 999)),
            List.of());
        assertThat(ImportParser.parse(archive(ACTIONS, bounds, NO_NOTES), TODAY, NOTE_FIELD))
            .as("1 and MAX_DAILY_COUNT are both valid counts, and must survive to the plan unchanged")
            .isEqualTo(new ParseOutcome.Planned(expected));

        assertThat(problems(ImportParser.parse(archive(ACTIONS, "date,action,count\r\n2026-08-01,Running,1000\r\n", NO_NOTES), TODAY, NOTE_FIELD)))
            .as("one past the cap is refused rather than clamped down to it")
            .containsExactly(new ImportProblem(TransferFiles.LOGS_FILE, 2, new ImportReason.CountOutOfRange(999)));
    }

    @Test
    void parse_rejectsDuplicateLogAndDuplicateNote() {
        final String twoLogs = "date,action,count\r\n2026-08-01,Running,1\r\n2026-08-01,Running,2\r\n";
        final String twoNotes = "date,content\r\n2026-08-01,\"first\"\r\n2026-08-01,\"second\"\r\n";

        final List<ImportProblem> expected = List.of(
            new ImportProblem(TransferFiles.LOGS_FILE, 3, new ImportReason.DuplicateLog("Running", LocalDate.of(2026, 8, 1))),
            new ImportProblem(TransferFiles.NOTES_FILE, 3, new ImportReason.DuplicateNote(LocalDate.of(2026, 8, 1))));
        assertThat(problems(ImportParser.parse(archive(ACTIONS, twoLogs, twoNotes), TODAY, NOTE_FIELD)))
            .as("one count per action per day, one note per day - the same uniqueness the database holds")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void parse_rejectsAnUnparseableDate() {
        final String badDates = "date,content\r\n07/08/2026,\"a note\"\r\n";

        assertThat(problems(ImportParser.parse(archive(ACTIONS, NO_LOGS, badDates), TODAY, NOTE_FIELD)))
            .as("a locale-formatted date read as ISO would silently land on the wrong day")
            .containsExactly(new ImportProblem(TransferFiles.NOTES_FILE, 2, new ImportReason.InvalidDate("07/08/2026")));
    }

    @Test
    void parse_rejectsAnEmptyNoteRatherThanStoringOne() {
        assertThat(problems(ImportParser.parse(archive(ACTIONS, NO_LOGS, "date,content\r\n2026-08-01,\"   \"\r\n"), TODAY, NOTE_FIELD)))
            .as("an empty note is no note, so a row describing one is a mistake rather than a deletion")
            .containsExactly(new ImportProblem(TransferFiles.NOTES_FILE, 2, new ImportReason.EmptyNote(LocalDate.of(2026, 8, 1))));
    }

    @Test
    void parse_appliesTheConfiguredNoteBound_notTheCatalogueDefault() {
        final String note = "date,content\r\n2026-08-01,\"" + "s".repeat(50) + "\"\r\n";
        final Map<String, String> members = archive(ACTIONS, NO_LOGS, note);

        assertThat(ImportParser.parse(members, TODAY, TextFields.note(50)))
            .as("a note exactly at the configured bound is accepted")
            .isInstanceOf(ParseOutcome.Planned.class);
        assertThat(problems(ImportParser.parse(members, TODAY, TextFields.note(49))))
            .as("the same note is refused once the deployment's bound is lower - an import cannot store what the note box would reject")
            .containsExactly(new ImportProblem(TransferFiles.NOTES_FILE, 2,
                new ImportReason.InvalidTextField(new TextOutcome.TooLong(TextFields.note(49)))));
    }

    @Test
    void parse_neverQuotesNoteContentInRejection() {
        final String secret = "date,content\r\n2026-08-01,\"" + "s".repeat(10_001) + "\"\r\n";

        final ParseOutcome outcome = ImportParser.parse(archive(ACTIONS, NO_LOGS, secret), TODAY, NOTE_FIELD);

        assertThat(problems(outcome).getFirst().reason())
            .as("a journal entry must not be echoed back out of a rejection banner any more than into a log")
            .isEqualTo(new ImportReason.InvalidTextField(new TextOutcome.TooLong(NOTE_FIELD)));
        assertThat(ImportService.message(problems(outcome).getFirst().reason()))
            .doesNotContain("sss")
            .isEqualTo("Note must be at most 10000 characters.");
    }

    @Test
    void parse_capsTheProblemsItListsButCountsThemAll() {
        final int badRows = ImportParser.MAX_REPORTED_PROBLEMS + 10;
        final StringBuilder manyBadRows = new StringBuilder(badRows * 32);
        manyBadRows.append("name,colour\r\n");
        for (int i = 0; i < badRows; i++) {
            manyBadRows.append("Action ").append(i).append(",not-a-colour\r\n");
        }

        final ParseOutcome outcome = ImportParser.parse(
            archive(manyBadRows.toString(), NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD);

        assertThat(outcome)
            .as("a file saved from the wrong tool generates a problem per row; a screen of identical complaints helps nobody")
            .isInstanceOf(ParseOutcome.Rejected.class);
        assertThat(problems(outcome))
            .as("the list is capped")
            .hasSize(ImportParser.MAX_REPORTED_PROBLEMS);
        assertThat(((ParseOutcome.Rejected) outcome).totalFound())
            .as("but the user is still told how many there really were")
            .isEqualTo(badRows);
    }

    private static Map<String, String> archive(final String actions, final String logs, final String notes) {
        return Map.of(
            TransferFiles.ACTIONS_FILE, actions,
            TransferFiles.LOGS_FILE, logs,
            TransferFiles.NOTES_FILE, notes);
    }

    private static List<ImportProblem> problems(final ParseOutcome outcome) {
        return outcome instanceof final ParseOutcome.Rejected rejected ? rejected.problems() : List.of();
    }
}
