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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.zodac.diurnal.note.AttachmentPolicy;
import net.zodac.diurnal.stub.StubAppConfig;
import net.zodac.diurnal.stub.StubNotesAttachmentsConfig;
import net.zodac.diurnal.text.TextField;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.user.PageSizePref;
import net.zodac.diurnal.user.StatFieldPref;
import net.zodac.diurnal.user.UserSettings;
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

    // The default policy - every extension accepted - standing in for what AttachmentPolicy resolves from NOTE_ATTACHMENT_EXTENSIONS at runtime.
    // attachment_extensionMustBeOneTheDeploymentAccepts covers a deployment that has narrowed it.
    private static final AttachmentPolicy POLICY = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.withDefaults());

    private static final byte[] FILE = "not really a png".getBytes(StandardCharsets.UTF_8);

    @Test
    void parse_readsCompleteArchive() {
        final ParseOutcome outcome = ImportParser.parse(archive(ACTIONS, LOGS, NOTES), TODAY, NOTE_FIELD, POLICY);

        final ImportPlan expected = new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")),
            List.of(new LogDraft(LocalDate.of(2026, 8, 1), "Running", 1)),
            List.of(new NoteDraft(LocalDate.of(2026, 8, 1), "Long run today.")), List.of(), null);
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
            List.of(new NoteDraft(LocalDate.of(2027, 1, 1), "a plan for the new year")), List.of(), null);
        assertThat(ImportParser.parse(archive(ACTIONS, LOGS, futureNote), TODAY, NOTE_FIELD, POLICY))
            .as("writing down a day in advance is legitimate, exactly as it is in the note box")
            .isEqualTo(new ParseOutcome.Planned(withFutureNote));

        assertThat(problems(ImportParser.parse(archive(ACTIONS, "date,action,count\r\n2027-01-01,Running,1\r\n", NOTES), TODAY, NOTE_FIELD, POLICY)))
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
        assertThat(problems(ImportParser.parse(unpacked(incomplete, Map.of()), TODAY, NOTE_FIELD, POLICY)))
            .as("an import replaces everything, so a partial archive would delete what its missing members describe")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void parse_requiresTheExactHeaderRow() {
        assertThat(problems(ImportParser.parse(archive("name,color\r\nRunning,#e11d48\r\n", LOGS, NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("guessing at a renamed column would let a file that means one thing be imported as another")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 1, new ImportReason.WrongHeader(TransferFiles.ACTIONS_HEADER)));
    }

    // The case above renames a column; this one changes how MANY there are - a separate arm of the header check, and the
    // mismatch a spreadsheet round-trip actually produces (a stray trailing comma, a dropped column). One case per member,
    // because each is read independently and any one of them failing is what stops the import.
    @Test
    void parse_requiresTheExactColumnCount() {
        final String extraActionColumn = "name,colour,icon\r\nRunning,#e11d48,x\r\n";
        assertThat(problems(ImportParser.parse(archive(extraActionColumn, LOGS, NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("an extra column means the file is not the export it claims to be")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 1, new ImportReason.WrongHeader(TransferFiles.ACTIONS_HEADER)));

        final String droppedLogColumn = "date,action\r\n2026-08-01,Running\r\n";
        assertThat(problems(ImportParser.parse(archive(ACTIONS, droppedLogColumn, NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("a dropped column would import every remaining value under the wrong name")
            .containsExactly(new ImportProblem(TransferFiles.LOGS_FILE, 1, new ImportReason.WrongHeader(TransferFiles.LOGS_HEADER)));

        final String extraNoteColumn = "date,content,extra\r\n2026-08-01,\"a note\",x\r\n";
        assertThat(problems(ImportParser.parse(archive(ACTIONS, LOGS, extraNoteColumn), TODAY, NOTE_FIELD, POLICY)))
            .as("the notes member is held to its own shape too")
            .containsExactly(new ImportProblem(TransferFiles.NOTES_FILE, 1, new ImportReason.WrongHeader(TransferFiles.NOTES_HEADER)));
    }

    @Test
    void parse_toleratesHeaderCasingAndPadding() {
        assertThat(ImportParser.parse(archive(" Name , COLOUR \r\nRunning,#e11d48\r\n", NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD, POLICY))
            .as("a spreadsheet may capitalise a header; the names and their order are what must match")
            .isEqualTo(new ParseOutcome.Planned(new ImportPlan(List.of(new ActionDraft("Running", "#e11d48")), List.of(), List.of(),
            List.of(), null)));
    }

    @Test
    void parse_reportsAnEmptyMember() {
        final ImportProblem expected = new ImportProblem(TransferFiles.ACTIONS_FILE, 1, new ImportReason.EmptyFile(TransferFiles.ACTIONS_HEADER));
        assertThat(problems(ImportParser.parse(archive("", LOGS, NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("a member with no header at all is not a member of an export")
            .containsExactly(expected);
    }

    @Test
    void parse_reportsAnUnreadableMember() {
        final ImportProblem expected = new ImportProblem(TransferFiles.NOTES_FILE, 2, new ImportReason.CsvUnreadable());
        assertThat(problems(ImportParser.parse(archive(ACTIONS, LOGS, "date,content\r\n2026-08-01,\"never closed\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("a CSV that cannot be parsed is located at the record that broke it")
            .containsExactly(expected);
    }

    @Test
    void parse_reportsRowWithWrongNumberOfColumns() {
        assertThat(problems(ImportParser.parse(archive("name,colour\r\nRunning\r\n", LOGS, NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("a short row is ambiguous rather than partially usable")
            .contains(new ImportProblem(TransferFiles.ACTIONS_FILE, 2, new ImportReason.WrongColumnCount(2, 1)));
    }

    @Test
    void parse_ignoresBlankRows() {
        assertThat(ImportParser.parse(archive("name,colour\r\n\r\nRunning,#e11d48\r\n\r\n", NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD, POLICY))
            .as("a trailing blank line from an editor is noise, not a row that failed")
            .isEqualTo(new ParseOutcome.Planned(new ImportPlan(List.of(new ActionDraft("Running", "#e11d48")), List.of(), List.of(),
            List.of(), null)));
    }

    @Test
    void parse_appliesTheSharedActionNameRules() {
        final String blankAndTooLong = "name,colour\r\n,#e11d48\r\n" + "x".repeat(101) + ",#0ea5e9\r\n";

        assertThat(problems(ImportParser.parse(archive(blankAndTooLong, NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("an import must not be a way to store a name no form would accept")
            .containsExactlyElementsOf(List.of(
                new ImportProblem(TransferFiles.ACTIONS_FILE, 2,
                    new ImportReason.InvalidTextField(new TextOutcome.Blank(TextFields.ACTION_NAME))),
                new ImportProblem(TransferFiles.ACTIONS_FILE, 3,
                    new ImportReason.InvalidTextField(new TextOutcome.TooLong(TextFields.ACTION_NAME)))));
    }

    @Test
    void parse_rejectsMalformedColourRatherThanDefaultingIt() {
        assertThat(problems(ImportParser.parse(archive("name,colour\r\nRunning,red\r\n", NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("silently substituting the default would produce an import that succeeded and is wrong")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 2, new ImportReason.InvalidColour()));
    }

    @Test
    void parse_rejectsDuplicateActionName() {
        final String duplicated = "name,colour\r\nRunning,#e11d48\r\nRunning,#0ea5e9\r\n";

        assertThat(problems(ImportParser.parse(archive(duplicated, NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("an action name is unique within an account, so a file cannot describe two")
            .containsExactly(new ImportProblem(TransferFiles.ACTIONS_FILE, 3, new ImportReason.DuplicateAction("Running")));
    }

    @Test
    void parse_rejectsLogNamingUndefinedAction() {
        final String unknown = "date,action,count\r\n2026-08-01,Swimming,1\r\n";

        assertThat(problems(ImportParser.parse(archive(ACTIONS, unknown, NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("a log's action is resolved from the archive's own actions, so an unknown name has nothing to point at")
            .containsExactly(new ImportProblem(TransferFiles.LOGS_FILE, 2, new ImportReason.UnknownAction("Swimming")));
    }

    @Test
    void parse_matchesLogsActionOnTheNormalisedName() {
        final String padded = "date,action,count\r\n2026-08-01,  Running ,2\r\n";

        final ImportPlan expected = new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")),
            List.of(new LogDraft(LocalDate.of(2026, 8, 1), "Running", 2)),
            List.of(), List.of(), null);
        assertThat(ImportParser.parse(archive(ACTIONS, padded, NO_NOTES), TODAY, NOTE_FIELD, POLICY))
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
        assertThat(problems(ImportParser.parse(archive(ACTIONS, outOfRange, NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("a file of ten thousand rows cannot afford a silent correction the user is not watching")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void parse_acceptsCountAtEachEndOfTheStoredRange() {
        final String bounds = "date,action,count\r\n2026-08-01,Running,1\r\n2026-08-02,Running,999\r\n";

        final ImportPlan expected = new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")),
            List.of(new LogDraft(LocalDate.of(2026, 8, 1), "Running", 1), new LogDraft(LocalDate.of(2026, 8, 2), "Running", 999)),
            List.of(), List.of(), null);
        assertThat(ImportParser.parse(archive(ACTIONS, bounds, NO_NOTES), TODAY, NOTE_FIELD, POLICY))
            .as("1 and MAX_DAILY_COUNT are both valid counts, and must survive to the plan unchanged")
            .isEqualTo(new ParseOutcome.Planned(expected));

        final ArchiveOutcome.Unpacked overCap = archive(ACTIONS, "date,action,count\r\n2026-08-01,Running,1000\r\n", NO_NOTES);
        assertThat(problems(ImportParser.parse(overCap, TODAY, NOTE_FIELD, POLICY)))
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
        assertThat(problems(ImportParser.parse(archive(ACTIONS, twoLogs, twoNotes), TODAY, NOTE_FIELD, POLICY)))
            .as("one count per action per day, one note per day - the same uniqueness the database holds")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void parse_rejectsAnUnparseableDate() {
        final String badDates = "date,content\r\n07/08/2026,\"a note\"\r\n";

        assertThat(problems(ImportParser.parse(archive(ACTIONS, NO_LOGS, badDates), TODAY, NOTE_FIELD, POLICY)))
            .as("a locale-formatted date read as ISO would silently land on the wrong day")
            .containsExactly(new ImportProblem(TransferFiles.NOTES_FILE, 2, new ImportReason.InvalidDate("07/08/2026")));
    }

    // The same rule on the OTHER dated member. Both walks read their date column through one helper, but each decides for
    // itself what to do with an unreadable one, and a log row silently skipped rather than reported would import an
    // archive as complete while dropping the day it could not read.
    @Test
    void parse_rejectsAnUnparseableDateInTheLogsMember() {
        final String badDate = "date,action,count\r\n07/08/2026,Running,1\r\n";

        assertThat(problems(ImportParser.parse(archive(ACTIONS, badDate, NO_NOTES), TODAY, NOTE_FIELD, POLICY)))
            .as("a log's date is read by the same ISO rule as a note's, and neither is guessed at")
            .containsExactly(new ImportProblem(TransferFiles.LOGS_FILE, 2, new ImportReason.InvalidDate("07/08/2026")));
    }

    @Test
    void parse_rejectsAnEmptyNoteRatherThanStoringOne() {
        assertThat(problems(ImportParser.parse(archive(ACTIONS, NO_LOGS, "date,content\r\n2026-08-01,\"   \"\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("an empty note is no note, so a row describing one is a mistake rather than a deletion")
            .containsExactly(new ImportProblem(TransferFiles.NOTES_FILE, 2, new ImportReason.EmptyNote(LocalDate.of(2026, 8, 1))));
    }

    @Test
    void parse_appliesTheConfiguredNoteBound_notTheCatalogueDefault() {
        final String note = "date,content\r\n2026-08-01,\"" + "s".repeat(50) + "\"\r\n";
        final ArchiveOutcome.Unpacked members = archive(ACTIONS, NO_LOGS, note);

        assertThat(ImportParser.parse(members, TODAY, TextFields.note(50), POLICY))
            .as("a note exactly at the configured bound is accepted")
            .isInstanceOf(ParseOutcome.Planned.class);
        assertThat(problems(ImportParser.parse(members, TODAY, TextFields.note(49), POLICY)))
            .as("the same note is refused once the deployment's bound is lower - an import cannot store what the note box would reject")
            .containsExactly(new ImportProblem(TransferFiles.NOTES_FILE, 2,
                new ImportReason.InvalidTextField(new TextOutcome.TooLong(TextFields.note(49)))));
    }

    @Test
    void parse_neverQuotesNoteContentInRejection() {
        final String secret = "date,content\r\n2026-08-01,\"" + "s".repeat(10_001) + "\"\r\n";

        final ParseOutcome outcome = ImportParser.parse(archive(ACTIONS, NO_LOGS, secret), TODAY, NOTE_FIELD, POLICY);

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
            archive(manyBadRows.toString(), NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD, POLICY);

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

    // ── attachments ───────────────────────────────────────────────────────────

    @Test
    void attachments_areReadWithTheBytesTheArchiveHeld() {
        final String manifest = "date,name,filename,file\r\n2026-08-01,route.png,route.png,attachments/0001.png\r\n";

        final ParseOutcome outcome = ImportParser.parse(attached(manifest, Map.of("attachments/0001.png", FILE)), TODAY, NOTE_FIELD, POLICY);

        assertThat(outcome)
            .as("a manifest row and the entry it names read into one draft")
            .isInstanceOf(ParseOutcome.Planned.class);
        final AttachmentDraft draft = ((ParseOutcome.Planned) outcome).plan().attachments().getFirst();
        assertThat(draft.date()).as("the day the row names").isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(draft.name()).as("the name the note embeds it by").isEqualTo("route.png");
        assertThat(draft.fileName()).as("the name the file was uploaded under").isEqualTo("route.png");
        assertThat(draft.file()).as("and the bytes the archive carried, so the plan needs no second read").isEqualTo(FILE);
    }

    @Test
    void attachments_keepBothNamesWhenTheyDiffer() {
        final String manifest = "date,name,filename,file\r\n2026-08-01,Berlin ticket,ticket-stub.png,attachments/0001.png\r\n";

        final ParseOutcome outcome = ImportParser.parse(attached(manifest, Map.of("attachments/0001.png", FILE)), TODAY, NOTE_FIELD, POLICY);

        final AttachmentDraft draft = ((ParseOutcome.Planned) outcome).plan().attachments().getFirst();
        assertThat(draft.name())
            .as("a file renamed before the export was taken must import under the name its note still embeds")
            .isEqualTo("Berlin ticket");
        assertThat(draft.fileName())
            .as("and must keep what it was uploaded as, or restoring a backup would quietly lose it")
            .isEqualTo("ticket-stub.png");
    }

    @Test
    void attachments_fileNameMustMeetTheSameRulesTheDisplayNameDoes() {
        final String manifest = "date,name,filename,file\r\n2026-08-01,Berlin ticket,ticket[1].png,attachments/0001.png\r\n";

        assertThat(problems(ImportParser.parse(attached(manifest, Map.of("attachments/0001.png", FILE)), TODAY, NOTE_FIELD, POLICY)))
            .as("both names end up sealed in the same row, and an archive is not trusted just because this application wrote the last one")
            .hasSize(1)
            .allSatisfy(problem -> assertThat(problem.reason()).isInstanceOf(ImportReason.InvalidTextField.class));
    }

    @Test
    void attachments_extensionIsJudgedOnTheFileNameNotTheDisplayName() {
        final AttachmentPolicy imagesOnly = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.accepting("png"));
        final String manifest = "date,name,filename,file\r\n2026-08-01,holiday.png,notes.pdf,attachments/0001.pdf\r\n";

        assertThat(problems(ImportParser.parse(attached(manifest, Map.of("attachments/0001.pdf", FILE)), TODAY, NOTE_FIELD, imagesOnly)))
            .as("the whitelist is about what a file IS, so a display name cannot launder a type the deployment refuses")
            .containsExactly(new ImportProblem(TransferFiles.ATTACHMENTS_FILE, 2,
                new ImportReason.AttachmentTypeNotAllowed("notes.pdf", List.of("png"))));
    }

    @Test
    void attachments_displayNameWithNoExtensionIsAcceptedWhenTheFileNameIsAllowed() {
        final AttachmentPolicy imagesOnly = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.accepting("png"));
        final String manifest = "date,name,filename,file\r\n2026-08-01,Berlin ticket,ticket-stub.png,attachments/0001.png\r\n";

        assertThat(ImportParser.parse(attached(manifest, Map.of("attachments/0001.png", FILE)), TODAY, NOTE_FIELD, imagesOnly))
            .as("renaming a file to something without an extension is allowed, so an export of one must import")
            .isInstanceOf(ParseOutcome.Planned.class);
    }

    @Test
    void attachments_memberIsOptional() {
        // An export taken before attachments existed is a complete export of what the account held then - refusing it would make every backup
        // from before the feature unrestorable.
        assertThat(ImportParser.parse(archive(ACTIONS, NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD, POLICY))
            .as("an archive with no attachments member reads as an account with no attachments")
            .isEqualTo(new ParseOutcome.Planned(new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")), List.of(), List.of(), List.of(), null)));
    }

    @Test
    void attachments_rowNamingNoEntryIsRefused() {
        final String manifest = "date,name,filename,file\r\n2026-08-01,route.png,route.png,attachments/0009.png\r\n";

        assertThat(problems(ImportParser.parse(attached(manifest, Map.of("attachments/0001.png", FILE)), TODAY, NOTE_FIELD, POLICY)))
            .as("the file column is a reference INTO the archive, so a row naming nothing describes bytes that were never there")
            .containsExactly(new ImportProblem(TransferFiles.ATTACHMENTS_FILE, 2,
                new ImportReason.MissingAttachmentFile("attachments/0009.png")));
    }

    @Test
    void attachments_rowNamingAnEmptyEntryIsRefused() {
        final String manifest = "date,name,filename,file\r\n2026-08-01,route.png,route.png,attachments/0001.png\r\n";

        assertThat(problems(ImportParser.parse(attached(manifest, Map.of("attachments/0001.png", new byte[0])), TODAY, NOTE_FIELD, POLICY)))
            .as("an empty file is nothing to attach, and the stored row could not represent one")
            .containsExactly(new ImportProblem(TransferFiles.ATTACHMENTS_FILE, 2, new ImportReason.EmptyAttachment("attachments/0001.png")));
    }

    @Test
    void attachments_twoOfOneNameOnTheSameDayAreRefused() {
        final String manifest = """
            date,name,filename,file\r
            2026-08-01,route.png,route.png,attachments/0001.png\r
            2026-08-01,route.png,route.png,attachments/0002.png\r
            """;

        assertThat(problems(ImportParser.parse(
            attached(manifest, Map.of("attachments/0001.png", FILE, "attachments/0002.png", FILE)), TODAY, NOTE_FIELD, POLICY)))
            .as("a note embeds a file BY name, so two of one name on a day would leave its token naming both")
            .containsExactly(new ImportProblem(TransferFiles.ATTACHMENTS_FILE, 3,
                new ImportReason.DuplicateAttachment("route.png", LocalDate.of(2026, 8, 1))));
    }

    @Test
    void attachments_theSameNameOnTwoDaysIsFine() {
        final String manifest = """
            date,name,filename,file\r
            2026-08-01,route.png,route.png,attachments/0001.png\r
            2026-08-02,route.png,route.png,attachments/0002.png\r
            """;

        assertThat(ImportParser.parse(
            attached(manifest, Map.of("attachments/0001.png", FILE, "attachments/0002.png", FILE)), TODAY, NOTE_FIELD, POLICY))
            .as("names are unique within a DAY, because that is the scope a note's token addresses")
            .isInstanceOf(ParseOutcome.Planned.class);
    }

    @Test
    void attachments_nameMustMeetTheSameRulesTypingOneDoes() {
        // A square bracket is what the note's own [[name]] token is written with, so a name carrying one could never be embedded.
        final String manifest = "date,name,filename,file\r\n2026-08-01,route[1].png,route[1].png,attachments/0001.png\r\n";

        assertThat(problems(ImportParser.parse(attached(manifest, Map.of("attachments/0001.png", FILE)), TODAY, NOTE_FIELD, POLICY)))
            .as("an import is a bulk version of writes the user could have made one at a time, so it applies the same field's rules")
            .hasSize(1)
            .allSatisfy(problem -> assertThat(problem.reason()).isInstanceOf(ImportReason.InvalidTextField.class));
    }

    @Test
    void attachments_extensionMustBeOneTheDeploymentAccepts() {
        final AttachmentPolicy imagesOnly = new AttachmentPolicy(StubAppConfig.empty(), StubNotesAttachmentsConfig.accepting("png"));
        final String manifest = "date,name,filename,file\r\n2026-08-01,notes.pdf,notes.pdf,attachments/0001.pdf\r\n";

        assertThat(problems(ImportParser.parse(attached(manifest, Map.of("attachments/0001.pdf", FILE)), TODAY, NOTE_FIELD, imagesOnly)))
            .as("an import must never be a way to store a file the upload endpoint would have refused")
            .containsExactly(new ImportProblem(TransferFiles.ATTACHMENTS_FILE, 2,
                new ImportReason.AttachmentTypeNotAllowed("notes.pdf", List.of("png"))));
    }

    @Test
    void attachments_malformedDateIsRefused() {
        final String manifest = "date,name,filename,file\r\n07/08/2026,route.png,route.png,attachments/0001.png\r\n";

        assertThat(problems(ImportParser.parse(attached(manifest, Map.of("attachments/0001.png", FILE)), TODAY, NOTE_FIELD, POLICY)))
            .as("the day column is read exactly as every other member's is")
            .containsExactly(new ImportProblem(TransferFiles.ATTACHMENTS_FILE, 2, new ImportReason.InvalidDate("07/08/2026")));
    }

    @Test
    void attachments_wrongHeaderIsRefused() {
        final String manifest = "date,name,filename,path\r\n2026-08-01,route.png,route.png,attachments/0001.png\r\n";

        assertThat(problems(ImportParser.parse(attached(manifest, Map.of("attachments/0001.png", FILE)), TODAY, NOTE_FIELD, POLICY)))
            .as("the header is matched exactly here too - a renamed column would let a file that means one thing be read as another")
            .containsExactly(new ImportProblem(TransferFiles.ATTACHMENTS_FILE, 1,
                new ImportReason.WrongHeader(TransferFiles.ATTACHMENTS_HEADER)));
    }

    @Test
    void parse_reportsProblemsMemberByMemberRatherThanByLineAcrossTheWholeArchive() {
        // The report is what a user works DOWN while correcting the file, so it reads top-to-bottom through the archive: every logs.csv problem,
        // then every notes.csv one, whatever line each is on. The logs problem is deliberately on a LATER line than the notes one, which is the
        // only arrangement that tells the member ordering apart from a plain sort by line number.
        final String logs = csv("date,action,count", "2026-08-01,Running,1", "2026-08-02,Running,1", "2026-08-03,Running,1",
            "2026-08-04,Running,5000");
        final String notes = "date,content\r\nnot-a-date,\"a note\"\r\n";

        final List<ImportProblem> expected = List.of(
            new ImportProblem(TransferFiles.LOGS_FILE, 5, new ImportReason.CountOutOfRange(999)),
            new ImportProblem(TransferFiles.NOTES_FILE, 2, new ImportReason.InvalidDate("not-a-date")));
        assertThat(problems(ImportParser.parse(archive(ACTIONS, logs, notes), TODAY, NOTE_FIELD, POLICY)))
            .as("problems are ordered by the member they are in first, and only then by line within it")
            .containsExactlyElementsOf(expected);
    }

    // ── settings.csv ───────────────────────────────────────────────────────

    @Test
    void settings_memberIsOptionalAndItsAbsenceLeavesTheAccountAlone() {
        // Unlike attachments.csv, absence does NOT mean "the account has none" - a preference always has a value, so the only other reading would
        // reset every one of them, changing the language out from under someone restoring a pre-settings backup.
        assertThat(ImportParser.parse(archive(ACTIONS, NO_LOGS, NO_NOTES), TODAY, NOTE_FIELD, POLICY))
            .as("an archive with no settings member describes no settings at all, not default settings")
            .isEqualTo(new ParseOutcome.Planned(new ImportPlan(
            List.of(new ActionDraft("Running", "#e11d48"), new ActionDraft("Reading", "#0ea5e9")), List.of(), List.of(), List.of(), null)));
    }

    @Test
    void settings_readsEveryScalarPreference() {
        final String settings = """
            setting,value\r
            theme,dark\r
            displayName,Ada Lovelace\r
            font,dyslexic\r
            language,es-ES\r
            calendarView,minimal\r
            noteColour,#123456\r
            timezone,Europe/London\r
            weekStart,sunday\r
            pageSize,25\r
            decimalPlaces,2\r
            showStatsSummary,false\r
            showNoteCounter,true\r
            """;

        final SettingsDraft expected = new SettingsDraft("minimal", 2, "Ada Lovelace", "dyslexic", "es-ES", "#123456", 25, true, false, "dark",
            "Europe/London", "sunday", null, null);
        assertThat(planned(ImportParser.parse(configured(settings), TODAY, NOTE_FIELD, POLICY)).settings())
            .as("every scalar preference the archive names should read into the value a Settings save would have stored")
            .isEqualTo(expected);
    }

    @Test
    void settings_aKeyTheFileOmitsIsLeftAloneRatherThanCleared() {
        assertThat(planned(ImportParser.parse(configured("setting,value\r\ntheme,dark\r\n"), TODAY, NOTE_FIELD, POLICY)).settings())
            .as("a preference always has a value, so a key the file does not carry is one the file is silent about")
            .isEqualTo(new SettingsDraft(null, null, null, null, null, null, null, null, null, "dark", null, null, null, null));
    }

    @Test
    void settings_blankResetsOnlyTheTwoPreferencesThatHaveOne() {
        final String blanks = "setting,value\r\ntimezone,\r\nweekStart,\r\n";

        assertThat(planned(ImportParser.parse(configured(blanks), TODAY, NOTE_FIELD, POLICY)).settings())
            .as("a blank timezone/week start is the explicit reset it is on every other surface, and is carried apart from an absent row")
            .isEqualTo(new SettingsDraft(null, null, null, null, null, null, null, null, null, null, "", "", null, null));

        assertThat(problems(ImportParser.parse(configured("setting,value\r\ntheme,\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("no other preference has a 'none' state, so a blank value for one is simply not a value it accepts")
            .containsExactly(new ImportProblem(TransferFiles.SETTINGS_FILE, 2,
                new ImportReason.InvalidSettingChoice("theme", "system, light, dark")));
    }

    @Test
    void settings_holdsTheDisplayNameToTheSameTextRulesTypingOneDoes() {
        // The one carried setting that is not a preference, and the only identity column the archive touches at all - so it goes through the
        // identical pipeline the Settings field does rather than being taken as read.
        assertThat(problems(ImportParser.parse(configured("setting,value\r\ndisplayName,A\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("a name typed into the Account card and one read out of an archive meet the same blank/length/content rules")
            .hasSize(1)
            .allSatisfy(problem -> assertThat(problem.reason()).isInstanceOf(ImportReason.InvalidTextField.class));

        assertThat(settings(ImportParser.parse(configured("setting,value\r\ndisplayName,  Ada Lovelace \r\n"), TODAY, NOTE_FIELD, POLICY))
            .displayName())
            .as("and it is stored in the SAME normalised form, so an archive cannot smuggle in padding a form would have removed")
            .isEqualTo("Ada Lovelace");
    }

    @Test
    void settings_refusesKeyTheApplicationDoesNotHave() {
        // Skipping it would be an import that succeeded and changed nothing - the silent wrong outcome this format refuses everywhere else.
        assertThat(problems(ImportParser.parse(configured("setting,value\r\nthem,dark\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("a mistyped setting key must be reported rather than quietly dropped")
            .containsExactly(new ImportProblem(TransferFiles.SETTINGS_FILE, 2, new ImportReason.UnknownSetting("them")));
    }

    @Test
    void settings_refusesTheSameKeyTwice() {
        assertThat(problems(ImportParser.parse(configured("setting,value\r\ntheme,dark\r\ntheme,light\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("two rows for one setting do not say which one the account should end up with")
            .containsExactly(new ImportProblem(TransferFiles.SETTINGS_FILE, 3, new ImportReason.DuplicateSetting("theme")));
    }

    @Test
    void settings_refusesValueTheSettingsPageWouldRefuse() {
        final String refusedSettings = """
            setting,value\r
            theme,neon\r
            noteColour,green\r
            showStatsSummary,yes\r
            pageSize,0\r
            decimalPlaces,9\r
            """;

        final List<ImportProblem> expected = List.of(
            new ImportProblem(TransferFiles.SETTINGS_FILE, 2,
                new ImportReason.InvalidSettingChoice("theme", "system, light, dark")),
            new ImportProblem(TransferFiles.SETTINGS_FILE, 3, new ImportReason.InvalidColour()),
            new ImportProblem(TransferFiles.SETTINGS_FILE, 4, new ImportReason.InvalidSettingChoice("showStatsSummary", "true, false")),
            new ImportProblem(TransferFiles.SETTINGS_FILE, 5,
                new ImportReason.SettingOutOfRange("pageSize", UserSettings.MIN_PAGE_SIZE, UserSettings.MAX_PAGE_SIZE)),
            new ImportProblem(TransferFiles.SETTINGS_FILE, 6,
                new ImportReason.SettingOutOfRange("decimalPlaces", UserSettings.MIN_DECIMAL_PLACES, UserSettings.MAX_DECIMAL_PLACES)));
        assertThat(problems(ImportParser.parse(configured(refusedSettings), TODAY, NOTE_FIELD, POLICY)))
            .as("an import is a bulk version of saves the user could have made one at a time, so it accepts exactly what those do")
            .containsExactlyElementsOf(expected);
    }

    @Test
    void settings_refusesBooleanThatIsNotTrueOrFalse() {
        // Boolean.parseBoolean reads anything that is not "true" as false, which would store the OPPOSITE of what a typo meant.
        assertThat(problems(ImportParser.parse(configured("setting,value\r\nshowNoteCounter,TRUE\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("a toggle takes exactly the two words the API writes, and nothing is coerced around them")
            .containsExactly(new ImportProblem(TransferFiles.SETTINGS_FILE, 2,
                new ImportReason.InvalidSettingChoice("showNoteCounter", "true, false")));
    }

    @Test
    void settings_readsThePerSectionPageSizeOverrides() {
        final String overrides = "setting,value\r\npageSize.notes,10\r\npageSize.actions,25\r\n";

        assertThat(planned(ImportParser.parse(configured(overrides), TODAY, NOTE_FIELD, POLICY)).settings())
            .as("the overrides should be stored in PageSection order, however the rows were laid out")
            .isEqualTo(new SettingsDraft(null, null, null, null, null, null, null, null, null, null, null, null,
            List.of(new PageSizePref("actions", 25), new PageSizePref("notes", 10)), null));
    }

    @Test
    void settings_refusesOverrideForSectionThatDoesNotExist() {
        assertThat(problems(ImportParser.parse(configured("setting,value\r\npageSize.frobnicate,10\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("a section key is as much a setting key as a scalar one, and is held to the same rule")
            .containsExactly(new ImportProblem(TransferFiles.SETTINGS_FILE, 2, new ImportReason.UnknownSetting("pageSize.frobnicate")));
    }

    @Test
    void settings_readsTheStatsArrangementInRowOrder() {
        final String arrangement = """
            setting,value\r
            statsField.current-streak,shown\r
            statsFieldName.current-streak,Days in a row\r
            statsField.total-count,hidden\r
            """;

        assertThat(settings(ImportParser.parse(configured(arrangement), TODAY, NOTE_FIELD, POLICY)).statsFields())
            .as("row order IS the arrangement, and a name row renames the stat above it")
            .isNotNull()
            .startsWith(new StatFieldPref("current-streak", true, "Days in a row"), new StatFieldPref("total-count", false, null));
    }

    @Test
    void settings_refusesStatNameWithNoArrangementRow() {
        // StatField.encode only consults a name for a key it was given an order for, so accepting this would lose the rename silently.
        assertThat(problems(ImportParser.parse(configured("setting,value\r\nstatsFieldName.total-count,Everything\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("a rename for a stat the file does not arrange is a row that could only be dropped")
            .containsExactly(new ImportProblem(TransferFiles.SETTINGS_FILE, 2, new ImportReason.UnknownSetting("statsFieldName.total-count")));
    }

    @Test
    void settings_refusesStatShownValueThatIsNeitherShownNorHidden() {
        assertThat(problems(ImportParser.parse(configured("setting,value\r\nstatsField.total-count,maybe\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("the arrangement's own column takes exactly the two words the export writes")
            .containsExactly(new ImportProblem(TransferFiles.SETTINGS_FILE, 2,
                new ImportReason.InvalidSettingChoice("statsField.total-count", "shown, hidden")));
    }

    @Test
    void settings_holdsRenamedStatToTheSameTextRulesTypingOneDoes() {
        final String arrangement = "setting,value\r\nstatsField.total-count,shown\r\nstatsFieldName.total-count,\"" + "x".repeat(200) + "\"\r\n";

        assertThat(problems(ImportParser.parse(configured(arrangement), TODAY, NOTE_FIELD, POLICY)))
            .as("a name typed into the Settings picker and one read out of an archive meet the same pipeline")
            .hasSize(1)
            .allSatisfy(problem -> assertThat(problem.reason()).isInstanceOf(ImportReason.InvalidTextField.class));
    }

    @Test
    void settings_refusesItsOwnHeaderBeingWrong() {
        assertThat(problems(ImportParser.parse(configured("key,value\r\ntheme,dark\r\n"), TODAY, NOTE_FIELD, POLICY)))
            .as("the header is matched exactly here too")
            .containsExactly(new ImportProblem(TransferFiles.SETTINGS_FILE, 1,
                new ImportReason.WrongHeader(TransferFiles.SETTINGS_HEADER)));
    }

    // A member's rows, CRLF-terminated as an exported archive carries them.
    private static String csv(final String header, final String... rows) {
        return header + "\r\n" + String.join("\r\n", rows) + "\r\n";
    }

    // An archive with no attachments member at all, which is what every case that is not ABOUT attachments wants - and is also the shape of an
    // export taken before attachments existed.
    private static ArchiveOutcome.Unpacked archive(final String actions, final String logs, final String notes) {
        return unpacked(Map.of(
            TransferFiles.ACTIONS_FILE, actions,
            TransferFiles.LOGS_FILE, logs,
            TransferFiles.NOTES_FILE, notes), Map.of());
    }

    private static ArchiveOutcome.Unpacked attached(final String attachments, final Map<String, byte[]> files) {
        return unpacked(Map.of(
            TransferFiles.ACTIONS_FILE, ACTIONS,
            TransferFiles.LOGS_FILE, NO_LOGS,
            TransferFiles.NOTES_FILE, NO_NOTES,
            TransferFiles.ATTACHMENTS_FILE, attachments), files);
    }

    private static ArchiveOutcome.Unpacked configured(final String settings) {
        return unpacked(Map.of(
            TransferFiles.ACTIONS_FILE, ACTIONS,
            TransferFiles.LOGS_FILE, NO_LOGS,
            TransferFiles.NOTES_FILE, NO_NOTES,
            TransferFiles.SETTINGS_FILE, settings), Map.of());
    }

    private static ArchiveOutcome.Unpacked unpacked(final Map<String, String> members, final Map<String, byte[]> files) {
        return new ArchiveOutcome.Unpacked(members, files);
    }

    private static List<ImportProblem> problems(final ParseOutcome outcome) {
        return outcome instanceof final ParseOutcome.Rejected rejected ? rejected.problems() : List.of();
    }

    private static SettingsDraft settings(final ParseOutcome outcome) {
        return Objects.requireNonNull(planned(outcome).settings(), "the archive carried a settings member, so the plan must hold one");
    }

    private static ImportPlan planned(final ParseOutcome outcome) {
        assertThat(outcome)
            .as("the archive should have been accepted")
            .isInstanceOf(ParseOutcome.Planned.class);
        return ((ParseOutcome.Planned) outcome).plan();
    }
}
