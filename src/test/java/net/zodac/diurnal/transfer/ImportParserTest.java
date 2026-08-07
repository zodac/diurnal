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
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ImportParser}: that an archive is read into exactly the values a form would have stored, that every rule the interactive
 * surfaces apply is applied here too, and that a rejection is located precisely enough to be fixed.
 */
class ImportParserTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 7);

    private static final String ACTIONS = "name,colour\r\nRunning,#e11d48\r\nReading,#0ea5e9\r\n";
    private static final String LOGS = "date,action,count\r\n2026-08-01,Running,1\r\n";
    private static final String NOTES = "date,content\r\n2026-08-01,\"Long run today.\"\r\n";

    @Test
    void parse_readsCompleteArchive() {
        final ParseOutcome outcome = ImportParser.parse(archive(ACTIONS, LOGS, NOTES), TODAY);

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
        assertThat(ImportParser.parse(archive(ACTIONS, LOGS, futureNote), TODAY))
            .as("writing down a day in advance is legitimate, exactly as it is in the note box")
            .isEqualTo(new ParseOutcome.Planned(withFutureNote));

        assertThat(problems(ImportParser.parse(archive(ACTIONS, "date,action,count\r\n2027-01-01,Running,1\r\n", NOTES), TODAY)))
            .as("claiming to have already performed an action tomorrow is not")
            .containsExactly(new ImportProblem(TransferFiles.LOGS_FILE, 2, "A log cannot be dated in the future (2027-01-01)."));
    }

    @Test
    void parse_requiresEveryMemberOfCompleteExport() {
        final Map<String, String> incomplete = new HashMap<>();
        incomplete.put(TransferFiles.ACTIONS_FILE, ACTIONS);

        final List<ImportProblem> expected = List.of(
            new ImportProblem("archive", 0, "The archive does not contain logs.csv, which a complete export always has."),
            new ImportProblem("archive", 0, "The archive does not contain notes.csv, which a complete export always has."));
        assertThat(problems(ImportParser.parse(incomplete, TODAY)))
            .as("an import replaces everything, so a partial archive would delete what its missing members describe")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void parse_requiresTheExactHeaderRow() {
        assertThat(problems(ImportParser.parse(archive("name,color\r\nRunning,#e11d48\r\n", LOGS, NOTES), TODAY)))
            .as("guessing at a renamed column would let a file that means one thing be imported as another")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 1, "The header row must be exactly name,colour."));
    }

    @Test
    void parse_toleratesHeaderCasingAndPadding() {
        assertThat(ImportParser.parse(archive(" Name , COLOUR \r\nRunning,#e11d48\r\n", "date,action,count\r\n", "date,content\r\n"), TODAY))
            .as("a spreadsheet may capitalise a header; the names and their order are what must match")
            .isEqualTo(new ParseOutcome.Planned(new ImportPlan(List.of(new ActionDraft("Running", "#e11d48")), List.of(), List.of())));
    }

    @Test
    void parse_reportsAnEmptyMember() {
        final ImportProblem expected = new ImportProblem(TransferFiles.ACTIONS_FILE, 1,
            "The file is empty - it must start with the header row name,colour.");
        assertThat(problems(ImportParser.parse(archive("", LOGS, NOTES), TODAY)))
            .as("a member with no header at all is not a member of an export")
            .containsExactly(expected);
    }

    @Test
    void parse_reportsAnUnreadableMember() {
        final ImportProblem expected = new ImportProblem(TransferFiles.NOTES_FILE, 2,
            "The file could not be read - a quoted value is never closed - check for an unbalanced \" character.");
        assertThat(problems(ImportParser.parse(archive(ACTIONS, LOGS, "date,content\r\n2026-08-01,\"never closed\r\n"), TODAY)))
            .as("a CSV that cannot be parsed is located at the record that broke it")
            .containsExactly(expected);
    }

    @Test
    void parse_reportsRowWithWrongNumberOfColumns() {
        assertThat(problems(ImportParser.parse(archive("name,colour\r\nRunning\r\n", LOGS, NOTES), TODAY)))
            .as("a short row is ambiguous rather than partially usable")
            .contains(new ImportProblem(TransferFiles.ACTIONS_FILE, 2, "Expected 2 columns but found 1."));
    }

    @Test
    void parse_ignoresBlankRows() {
        assertThat(ImportParser.parse(archive("name,colour\r\n\r\nRunning,#e11d48\r\n\r\n", "date,action,count\r\n", "date,content\r\n"), TODAY))
            .as("a trailing blank line from an editor is noise, not a row that failed")
            .isEqualTo(new ParseOutcome.Planned(new ImportPlan(List.of(new ActionDraft("Running", "#e11d48")), List.of(), List.of())));
    }

    @Test
    void parse_appliesTheSharedActionNameRules() {
        final String blankAndTooLong = "name,colour\r\n,#e11d48\r\n" + "x".repeat(101) + ",#0ea5e9\r\n";

        assertThat(problems(ImportParser.parse(archive(blankAndTooLong, "date,action,count\r\n", "date,content\r\n"), TODAY)))
            .as("an import must not be a way to store a name no form would accept")
            .containsExactlyElementsOf(List.of(
                new ImportProblem(TransferFiles.ACTIONS_FILE, 2, "Action name cannot be empty."),
                new ImportProblem(TransferFiles.ACTIONS_FILE, 3, "Action name must be at most 100 characters.")));
    }

    @Test
    void parse_rejectsMalformedColourRatherThanDefaultingIt() {
        assertThat(problems(ImportParser.parse(archive("name,colour\r\nRunning,red\r\n", "date,action,count\r\n", "date,content\r\n"), TODAY)))
            .as("silently substituting the default would produce an import that succeeded and is wrong")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 2, "The colour must be a hex value such as #6366f1."));
    }

    @Test
    void parse_rejectsDuplicateActionName() {
        final String duplicated = "name,colour\r\nRunning,#e11d48\r\nRunning,#0ea5e9\r\n";

        assertThat(problems(ImportParser.parse(archive(duplicated, "date,action,count\r\n", "date,content\r\n"), TODAY)))
            .as("an action name is unique within an account, so a file cannot describe two")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 3, "The action 'Running' appears more than once."));
    }

    @Test
    void parse_rejectsLogNamingUndefinedAction() {
        final String unknown = "date,action,count\r\n2026-08-01,Swimming,1\r\n";

        assertThat(problems(ImportParser.parse(archive(ACTIONS, unknown, NOTES), TODAY)))
            .as("a log's action is resolved from the archive's own actions, so an unknown name has nothing to point at")
            .containsExactly(new ImportProblem(TransferFiles.LOGS_FILE, 2, "No action named 'Swimming' is defined in actions.csv."));
    }

    @Test
    void parse_matchesLogsActionOnTheNormalisedName() {
        final String padded = "date,action,count\r\n2026-08-01,  Running ,2\r\n";

        final ImportPlan expected = new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")),
            List.of(new LogDraft(LocalDate.of(2026, 8, 1), "Running", 2)),
            List.of());
        assertThat(ImportParser.parse(archive(ACTIONS, padded, "date,content\r\n"), TODAY))
            .as("a difference the user cannot see must not refuse their file")
            .isEqualTo(new ParseOutcome.Planned(expected));
    }

    @Test
    void parse_rejectsCountOutsideStoredRangeRatherThanClamping() {
        final String outOfRange = "date,action,count\r\n2026-08-01,Running,0\r\n2026-08-02,Running,1500\r\n2026-08-03,Running,many\r\n";

        final List<ImportProblem> expected = List.of(
            new ImportProblem(TransferFiles.LOGS_FILE, 2, "The count must be between 1 and 999."),
            new ImportProblem(TransferFiles.LOGS_FILE, 3, "The count must be between 1 and 999."),
            new ImportProblem(TransferFiles.LOGS_FILE, 4, "'many' is not a whole number."));
        assertThat(problems(ImportParser.parse(archive(ACTIONS, outOfRange, NOTES), TODAY)))
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
        assertThat(ImportParser.parse(archive(ACTIONS, bounds, "date,content\r\n"), TODAY))
            .as("1 and MAX_DAILY_COUNT are both valid counts, and must survive to the plan unchanged")
            .isEqualTo(new ParseOutcome.Planned(expected));

        assertThat(problems(ImportParser.parse(archive(ACTIONS, "date,action,count\r\n2026-08-01,Running,1000\r\n", "date,content\r\n"), TODAY)))
            .as("one past the cap is refused rather than clamped down to it")
            .containsExactly(new ImportProblem(TransferFiles.LOGS_FILE, 2, "The count must be between 1 and 999."));
    }

    @Test
    void parse_rejectsDuplicateLogAndDuplicateNote() {
        final String twoLogs = "date,action,count\r\n2026-08-01,Running,1\r\n2026-08-01,Running,2\r\n";
        final String twoNotes = "date,content\r\n2026-08-01,\"first\"\r\n2026-08-01,\"second\"\r\n";

        final List<ImportProblem> expected = List.of(
            new ImportProblem(TransferFiles.LOGS_FILE, 3, "There is already a log for 'Running' on 2026-08-01."),
            new ImportProblem(TransferFiles.NOTES_FILE, 3, "There is already a note for 2026-08-01."));
        assertThat(problems(ImportParser.parse(archive(ACTIONS, twoLogs, twoNotes), TODAY)))
            .as("one count per action per day, one note per day - the same uniqueness the database holds")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void parse_rejectsAnUnparseableDate() {
        final String badDates = "date,content\r\n07/08/2026,\"a note\"\r\n";

        assertThat(problems(ImportParser.parse(archive(ACTIONS, "date,action,count\r\n", badDates), TODAY)))
            .as("a locale-formatted date read as ISO would silently land on the wrong day")
            .containsExactly(new ImportProblem(TransferFiles.NOTES_FILE, 2, "'07/08/2026' is not a date in YYYY-MM-DD form."));
    }

    @Test
    void parse_rejectsAnEmptyNoteRatherThanStoringOne() {
        assertThat(problems(ImportParser.parse(archive(ACTIONS, "date,action,count\r\n", "date,content\r\n2026-08-01,\"   \"\r\n"), TODAY)))
            .as("an empty note is no note, so a row describing one is a mistake rather than a deletion")
            .containsExactly(new ImportProblem(TransferFiles.NOTES_FILE, 2, "The note for 2026-08-01 is empty - delete the row instead."));
    }

    @Test
    void parse_neverQuotesNoteContentInRejection() {
        final String secret = "date,content\r\n2026-08-01,\"" + "s".repeat(10_001) + "\"\r\n";

        final ParseOutcome outcome = ImportParser.parse(archive(ACTIONS, "date,action,count\r\n", secret), TODAY);

        assertThat(problems(outcome).getFirst().reason())
            .as("a journal entry must not be echoed back out of a rejection banner any more than into a log")
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
            archive(manyBadRows.toString(), "date,action,count\r\n", "date,content\r\n"), TODAY);

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
