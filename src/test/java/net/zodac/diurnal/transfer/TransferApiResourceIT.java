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

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.BAD_REQUEST;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static net.zodac.diurnal.transfer.TransferFiles.ACTIONS_FILE;
import static net.zodac.diurnal.transfer.TransferFiles.ALL_FILES;
import static net.zodac.diurnal.transfer.TransferFiles.LOGS_FILE;
import static net.zodac.diurnal.transfer.TransferFiles.NOTES_FILE;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.note.Note;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * The public data export/import API end to end: that an export round-trips through an import unchanged, that an import genuinely REPLACES, that a
 * refused archive writes nothing at all, and that one account's archive cannot reach another's data.
 *
 * <p>
 * The parsing and validation rules themselves are unit-tested in {@link ImportParserTest}; this pins the persistence, the transaction behaviour and
 * the JSON translation.
 */
@QuarkusTest
@TestSecurity(user = TransferApiResourceIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class TransferApiResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "transfer-api-it@lt.test";
    private static final String OTHER = "transfer-api-other@lt.test";

    private static final String EXPORT_PATH = "/api/v1/data/export";
    private static final String IMPORT_PATH = "/api/v1/data/import";
    private static final String PREVIEW_PATH = "/api/v1/data/import/preview";
    private static final String APPLICATION_ZIP = "application/zip";

    private UUID userId;
    private UUID otherUserId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Transfer User").id;
        otherUserId = newUser(OTHER, "Other User").id;
    }

    @Test
    void export_writesEveryMemberWithItsHeader() {
        seedPrimary();

        final Map<String, String> members = unpack(exportArchive());

        assertThat(members)
            .as("a complete export always holds all three members")
            .containsOnlyKeys(ALL_FILES);
        assertThat(members)
            .as("actions are written name-then-colour, ordered by name")
            .containsEntry(ACTIONS_FILE, "﻿name,colour\r\nReading,#0ea5e9\r\nRunning,#e11d48\r\n");
        assertThat(members)
            .as("a log names its action rather than pointing at an internal identifier, ordered by date then action")
            .containsEntry(LOGS_FILE, "﻿date,action,count\r\n2026-06-13,Running,2\r\n2026-06-14,Reading,1\r\n2026-06-14,Running,5\r\n");
        assertThat(members)
            .as("note content is written in the clear, quoted where it holds a line break")
            .containsEntry(NOTES_FILE, "﻿date,content\r\n2026-06-13,\"Line one\nLine two\"\r\n2026-06-14,\"A note, with a comma\"\r\n");
    }

    @Test
    void exportThenImport_leavesTheAccountHoldingExactlyWhatItHeld() {
        seedPrimary();
        final byte[] archive = exportArchive();

        given().contentType(APPLICATION_ZIP).body(archive)
            .post(IMPORT_PATH)
            .then().statusCode(OK)
            .body("actions", Matchers.is(2))
            .body("logs", Matchers.is(3))
            .body("notes", Matchers.is(2));

        assertThat(unpack(exportArchive()))
            .as("an export, imported, must produce the same export again - otherwise the archive is not a backup")
            .isEqualTo(unpack(archive));
    }

    @Test
    void importData_replacesEverythingTheAccountHeld() {
        seedPrimary();

        given().contentType(APPLICATION_ZIP)
            .body(archiveOf(
                "name,colour\r\nSwimming,#22c55e\r\n",
                "date,action,count\r\n2026-06-10,Swimming,3\r\n",
                "date,content\r\n2026-06-10,\"a fresh start\"\r\n"))
            .post(IMPORT_PATH)
            .then().statusCode(OK)
            .body("actions", Matchers.is(1))
            .body("replacedActions", Matchers.is(2))
            .body("replacedLogs", Matchers.is(3))
            .body("replacedNotes", Matchers.is(2));

        runInTx(() -> {
            assertThat(Action.<Action>list("userId", userId).stream().map(action -> action.name).toList())
                .as("every action the account held is gone, replaced by the archive's own")
                .containsExactly("Swimming");
            assertThat(ActionLog.<ActionLog>list("userId", userId))
                .as("the previous logs went with them")
                .hasSize(1);
            assertThat(storedNoteContent(userId, LocalDate.of(2026, 6, 10)))
                .as("and the imported note is stored sealed, readable only through the owner's key")
                .isEqualTo("a fresh start");
            assertThat(Note.<Note>list("userId", userId))
                .as("the notes the account held are gone too")
                .hasSize(1);
        });
    }

    @Test
    void importData_writesNothingAtAllWhenAnyRowIsRefused() {
        seedPrimary();

        given().contentType(APPLICATION_ZIP)
            .body(archiveOf(
                "name,colour\r\nSwimming,#22c55e\r\n",
                "date,action,count\r\n2026-06-10,Swimming,3\r\n2026-06-11,Swimming,9999\r\n",
                "date,content\r\n"))
            .post(IMPORT_PATH)
            .then().statusCode(BAD_REQUEST)
            .body("problems.size()", Matchers.is(1))
            .body("problems[0].file", Matchers.equalTo(LOGS_FILE))
            .body("problems[0].line", Matchers.is(3));

        // The deletes run before the inserts, so a rejection reached part-way through would leave an emptied account behind if the transaction did
        // not roll back. This is the @RollbackOnErrorStatus binding doing its job.
        runInTx(() -> assertThat(Action.<Action>list("userId", userId))
            .as("a refused import must leave the account exactly as it was, not partially wiped")
            .hasSize(2));
    }

    @Test
    void preview_reportsWhatWouldHappenAndWritesNothing() {
        seedPrimary();

        given().contentType(APPLICATION_ZIP)
            .body(archiveOf(
                "name,colour\r\nSwimming,#22c55e\r\n",
                "date,action,count\r\n",
                "date,content\r\n"))
            .post(PREVIEW_PATH)
            .then().statusCode(OK)
            .body("actions", Matchers.is(1))
            .body("logs", Matchers.is(0))
            .body("replacedActions", Matchers.is(2))
            .body("replacedLogs", Matchers.is(3))
            .body("replacedNotes", Matchers.is(2));

        runInTx(() -> assertThat(Action.<Action>list("userId", userId))
            .as("a preview is the import with the write left off - it must touch nothing")
            .hasSize(2));
    }

    @Test
    void importData_refusesSomethingThatIsNotAnArchive() {
        given().contentType(APPLICATION_ZIP).body("not a zip".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .post(IMPORT_PATH)
            .then().statusCode(BAD_REQUEST)
            .body("message", Matchers.equalTo("The uploaded file is not a ZIP archive."))
            .body("problems.size()", Matchers.is(0));
    }

    @Test
    void importData_refusesAnIncompleteArchive() {
        given().contentType(APPLICATION_ZIP)
            .body(TransferArchive.pack(Map.of(ACTIONS_FILE, "name,colour\r\n"), Instant.now()))
            .post(IMPORT_PATH)
            .then().statusCode(BAD_REQUEST)
            .body("totalProblems", Matchers.is(2))
            .body("problems.file", Matchers.everyItem(Matchers.equalTo("archive")));
    }

    @Test
    void importData_neverReachesAnotherAccountsData() {
        runInTx(() -> {
            final Action otherAction = newAction(otherUserId, "Their Action");
            newLog(otherUserId, otherAction.id, LocalDate.of(2026, 6, 14), 4);
            newNote(otherUserId, LocalDate.of(2026, 6, 14), "their note");
        });

        given().contentType(APPLICATION_ZIP)
            .body(archiveOf("name,colour\r\nMine,#22c55e\r\n", "date,action,count\r\n", "date,content\r\n"))
            .post(IMPORT_PATH)
            .then().statusCode(OK)
            .body("replacedActions", Matchers.is(0));

        runInTx(() -> {
            assertThat(Action.<Action>list("userId", otherUserId))
                .as("the wipe is scoped to the acting user; another account's actions are untouched")
                .hasSize(1);
            assertThat(storedNoteContent(otherUserId, LocalDate.of(2026, 6, 14)))
                .as("and so are their notes")
                .isEqualTo("their note");
        });
    }

    private void seedPrimary() {
        runInTx(() -> {
            final Action running = newAction(userId, "Running");
            running.colour = "#e11d48";
            final Action reading = newAction(userId, "Reading");
            reading.colour = "#0ea5e9";

            newLog(userId, running.id, LocalDate.of(2026, 6, 14), 5);
            newLog(userId, running.id, LocalDate.of(2026, 6, 13), 2);
            newLog(userId, reading.id, LocalDate.of(2026, 6, 14), 1);

            newNote(userId, LocalDate.of(2026, 6, 14), "A note, with a comma");
            newNote(userId, LocalDate.of(2026, 6, 13), "Line one\nLine two");
        });
    }

    @Test
    void export_namesTheArchiveWithTheUsersOwnLocalDateAndTime() {
        // 23:30 UTC is already 09:30 the NEXT day in Sydney, so this pins both halves at once: that the stamp carries a time at all, and that the
        // time (and therefore the date it rolls over) is resolved in the user's timezone rather than the server's.
        freezeInstant(Instant.parse("2026-06-14T23:30:15Z"), ZoneOffset.UTC);
        runInTx(() -> User.<User>findById(userId).timezone = "Australia/Sydney");

        given().get(EXPORT_PATH)
            .then().statusCode(OK)
            .header("Content-Disposition", Matchers.equalTo("attachment; filename=\"diurnal-export-2026-06-15T09-30-15.zip\""));
    }

    @Test
    void export_namesTwoArchivesTakenTheSameDayDifferently() {
        freezeInstant(Instant.parse("2026-06-15T08:00:00Z"), ZoneOffset.UTC);
        final String morning = exportFileName();

        freezeInstant(Instant.parse("2026-06-15T16:45:30Z"), ZoneOffset.UTC);

        assertThat(exportFileName())
            .as("two exports on one day must not collide, or the browser quietly renames the second and neither says which is which")
            .isNotEqualTo(morning);
    }

    private static String exportFileName() {
        final String disposition = given().get(EXPORT_PATH)
            .then().statusCode(OK)
            .extract().header("Content-Disposition");
        return disposition.replace("attachment; filename=\"", "").replace("\"", "");
    }

    private static byte[] exportArchive() {
        return given().get(EXPORT_PATH)
            .then().statusCode(OK)
            .extract().asByteArray();
    }

    private static byte[] archiveOf(final String actions, final String logs, final String notes) {
        return TransferArchive.pack(Map.of(
            ACTIONS_FILE, actions,
            LOGS_FILE, logs,
            NOTES_FILE, notes), Instant.now());
    }

    private static Map<String, String> unpack(final byte[] archive) {
        final ArchiveOutcome outcome = TransferArchive.unpack(archive);
        assertThat(outcome)
            .as("the exported archive must be readable")
            .isInstanceOf(ArchiveOutcome.Unpacked.class);
        return outcome instanceof ArchiveOutcome.Unpacked(final Map<String, String> members) ? members : Map.of();
    }
}
