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
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static net.zodac.diurnal.http.HttpStatusCodes.UNPROCESSABLE_ENTITY;
import static net.zodac.diurnal.transfer.TransferFiles.ACTIONS_FILE;
import static net.zodac.diurnal.transfer.TransferFiles.LOGS_FILE;
import static net.zodac.diurnal.transfer.TransferFiles.NOTES_FILE;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * The Settings import panel's refusal list - the half of a rejection the public API's JSON translation cannot show
 * ({@link TransferApiResourceIT}): how a problem is worded and marked up for a page.
 *
 * <p>
 * The parsing and validation rules themselves are unit-tested in {@link ImportParserTest}; this pins the presentation.
 */
@QuarkusTest
@TestSecurity(user = TransferInternalResourceIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
class TransferInternalResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "transfer-internal-it@lt.test";

    private static final String IMPORT_PATH = "/internal/data/import";
    private static final String APPLICATION_ZIP = "application/zip";
    private static final int CHUNK_BYTES = 64 * 1024;
    private static final String CRLF = "\r\n";

    @Override
    protected void createDbState() {
        newUser(PRIMARY, "Transfer User");
    }

    @Test
    void importData_incompleteArchive_namesEachMissingMemberInBoldAndLeadsWithNoMemberOfItsOwn() {
        final String panel = importPanel(TransferArchive.pack(Map.of(ACTIONS_FILE, "name,colour\r\n"), Instant.now()));

        assertThat(panel)
            .as("a missing member is named in the sentence itself, in bold - so the reason must reach the page as markup rather than as a value")
            .contains("The archive does not contain <strong>" + LOGS_FILE + "</strong>.")
            .contains("The archive does not contain <strong>" + NOTES_FILE + "</strong>.")
            .doesNotContain("&mdash;");
    }

    @Test
    void importData_rowProblem_leadsWithItsMemberAndEscapesTheArchivesOwnTextExactlyOnce() {
        // The panel renders a reason as MARKUP (it is already-rendered HTML, not a value - see partials/import-panel.html), so the archive's own
        // text must arrive from partials/import-reason.html already escaped: escaped there once, and not again here.
        final String panel = importPanel(archiveOf(
            "name,colour\r\nSwimming,#22c55e\r\n",
            "date,action,count\r\n2026-06-14,<script>alert(1)</script>,3\r\n",
            "date,content\r\n"
        ));

        assertThat(panel)
            .as("a problem INSIDE a member still leads with that member's name and the line to correct")
            .contains("<strong>" + LOGS_FILE + "</strong>, line 2")
            .doesNotContain("<script>alert(1)</script>")
            .contains("&lt;script&gt;")
            .doesNotContain("&amp;lt;")
            .contains("is defined in <strong>" + ACTIONS_FILE + "</strong>.");
    }

    @Test
    void importData_wrongHeader_chipsEachColumnNameButNotTheCommasBetweenThem() {
        final String panel = importPanel(archiveOf(
            "name,color\r\n",
            "date,action,count\r\n",
            "date,content\r\n"
        ));

        assertThat(panel)
            .as("a chip marks one column name, so the commas separating them stay plain sentence text - and the chips have to survive as markup")
            .contains("The header row must be exactly <code>name</code>, <code>colour</code>.");
    }

    @Test
    void previewImport_readableArchive_reportsWhatItHoldsAndWritesNothing() {
        final String panel = previewPanel(completeArchive());

        assertThat(panel)
            .as("the preview states the archive's real figures, singular-aware, so the confirmation that follows is an informed one")
            .contains("This archive holds 1 action, 1 day count and 1 note.")
            .contains("You have nothing tracked yet, so nothing will be removed.");
    }

    @Test
    void importData_readableArchive_reportsWhatItImported() {
        final String panel = given().contentType(APPLICATION_ZIP).body(completeArchive())
            .post(IMPORT_PATH)
            .then().statusCode(OK)
            .extract().body().asString();

        assertThat(panel)
            .as("the applied banner is the past-tense counterpart of the preview's own figures")
            .contains("Imported 1 action, 1 day count and 1 note.");
    }

    @Test
    void importData_notAZipArchive_saysSoRatherThanListingRowProblems() {
        final String panel = importPanel("this is not a ZIP archive".getBytes(StandardCharsets.UTF_8));

        assertThat(panel)
            .as("an unopenable archive is refused as a whole, with its own banner rather than the generic one")
            .contains("The uploaded file is not a ZIP archive.")
            .doesNotContain("The archive is an invalid export");
    }

    @Test
    void importData_truncatedArchive_reportsWhereTheReaderGaveUp() {
        final byte[] complete = completeArchive();
        final byte[] truncated = Arrays.copyOf(complete, complete.length / 2);

        assertThat(importPanel(truncated))
            .as("a ZIP that starts well and ends mid-entry is refused with the reader's own account of where it stopped")
            .contains("The uploaded archive could not be read:");
    }

    @Test
    void importData_tooManyEntries_namesTheCapItExceeded() {
        assertThat(importPanel(zipOfEmptyEntries(TransferArchive.MAX_ENTRIES + 1)))
            .as("the entry cap is named in the sentence, so the number has to reach the page as a value")
            .contains("The uploaded archive holds more than " + TransferArchive.MAX_ENTRIES + " entries.");
    }

    @Test
    void importData_memberLargerThanTheDecompressedCap_isRefusedOnWhatItActuallyDecompressesTo() {
        // The archive posted here is a few dozen KB - it is what it decompresses to that is refused, which is the whole point of the cap.
        assertThat(importPanel(oversizedArchive()))
            .as("a small upload that decompresses past the member cap is refused")
            .contains("The uploaded archive is too large once decompressed.");
    }

    @Test
    void importData_unbalancedQuote_saysWhichCharacterToLookFor() {
        final String panel = importPanel(archiveOf(
            "name,colour\r\nRunning,#e11d48\r\n",
            "date,action,count\r\n",
            "date,content\r\n2026-06-14,\"never closed\r\n"
        ));

        assertThat(panel)
            .as("an unterminated quoted value is reported as such, naming the character to check for")
            .contains("The file could not be read - a quoted value is never closed - check for an unbalanced &quot; character.");
    }

    @Test
    void importData_emptyMember_chipsTheHeaderRowItShouldStartWith() {
        final String panel = importPanel(archiveOf("", "date,action,count\r\n", "date,content\r\n"));

        assertThat(panel)
            .as("an empty member is told which header row it must start with, each column name chipped")
            .contains("The file is empty - it must start with the header row <code>name</code>, <code>colour</code>.");
    }

    @Test
    void importData_rowWithTooFewColumns_reportsBothCounts() {
        final String panel = importPanel(archiveOf(
            "name,colour\r\nRunning\r\n",
            "date,action,count\r\n",
            "date,content\r\n"
        ));

        assertThat(panel)
            .as("both the expected and the found column count reach the page")
            .contains("Expected 2 columns but found 1.");
    }

    @Test
    void importData_actionRowProblems_areEachWordedForThePage() {
        final String panel = importPanel(archiveOf(
            "name,colour\r\nRunning,#e11d48\r\nRunning,#0ea5e9\r\n,#22c55e\r\nCycling,not-a-colour\r\n",
            "date,action,count\r\n",
            "date,content\r\n"
        ));

        assertThat(panel)
            // The archive's own text arrives escaped - an apostrophe in a wording reaches the page as &#39; - which is the panel rendering the
            // reason as already-escaped markup rather than escaping it a second time.
            .as("every refusal an actions.csv row can earn is worded in the panel's own list")
            .contains("The action &#39;Running&#39; appears more than once.")
            .contains("Action name cannot be empty.")
            .contains("The colour must be a hex value such as #6366f1.");
    }

    @Test
    void importData_logRowProblems_areEachWordedForThePage() {
        final String logRows = csv("date,action,count", "2026-12-25,Running,1", "2026-06-14,Running,many", "2026-06-14,Running,1000",
            "2026-06-13,Running,1", "2026-06-13,Running,2");

        final String panel = importPanel(archiveOf(
            "name,colour\r\nRunning,#e11d48\r\n",
            logRows,
            "date,content\r\n"
        ));

        assertThat(panel)
            .as("every refusal a logs.csv row can earn is worded in the panel's own list")
            .contains("A log cannot be dated in the future (2026-12-25).")
            .contains("&#39;many&#39; is not a whole number.")
            .contains("The count must be between 1 and 999.")
            .contains("There is already a log for &#39;Running&#39; on 2026-06-13.");
    }

    @Test
    void importData_noteRowProblems_areEachWordedForThePage() {
        final String panel = importPanel(archiveOf(
            "name,colour\r\nRunning,#e11d48\r\n",
            "date,action,count\r\n",
            "date,content\r\n2026-06-10,\"\"\r\n2026-06-11,\"Fine\"\r\n2026-06-11,\"Again\"\r\n07/08/2026,\"Bad date\"\r\n"
        ));

        assertThat(panel)
            .as("every refusal a notes.csv row can earn is worded in the panel's own list, and none of them quotes the note's content")
            .contains("The note for 2026-06-10 is empty - delete the row instead.")
            .contains("There is already a note for 2026-06-11.")
            .contains("&#39;07/08/2026&#39; is not a date in YYYY-MM-DD form.")
            .doesNotContain("Fine")
            .doesNotContain("Bad date");
    }

    private static String importPanel(final byte[] archive) {
        return given().contentType(APPLICATION_ZIP).body(archive)
            .post(IMPORT_PATH)
            .then().statusCode(UNPROCESSABLE_ENTITY)
            .extract().body().asString();
    }

    private static String previewPanel(final byte[] archive) {
        return given().contentType(APPLICATION_ZIP).body(archive)
            .post(IMPORT_PATH + "/preview")
            .then().statusCode(OK)
            .extract().body().asString();
    }

    private static byte[] completeArchive() {
        return archiveOf(
            "name,colour\r\nRunning,#e11d48\r\n",
            "date,action,count\r\n2026-06-14,Running,3\r\n",
            "date,content\r\n2026-06-14,\"Long run today.\"\r\n"
        );
    }

    // A member's rows, CRLF-terminated as an exported archive carries them. Written as a join rather than one concatenated literal because a
    // multi-line concatenation is a Qodana finding, and the text block it suggests cannot express \r\n without escaping every line ending.
    private static String csv(final String header, final String... rows) {
        return header + CRLF + String.join(CRLF, rows) + CRLF;
    }

    private static byte[] archiveOf(final String actions, final String logs, final String notes) {
        return TransferArchive.pack(Map.of(ACTIONS_FILE, actions, LOGS_FILE, logs, NOTES_FILE, notes), Instant.now());
    }

    // TransferArchive.pack writes only the three members the format recognises, so an archive holding more entries than the cap has to be built
    // here. Every entry counts towards the cap, recognised or not, which is what stops a padded upload from being unpacked at all.
    private static byte[] zipOfEmptyEntries(final int entryCount) {
        final ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (final ZipOutputStream archive = new ZipOutputStream(packed, StandardCharsets.UTF_8)) {
            for (int i = 0; i < entryCount; i++) {
                archive.putNextEntry(new ZipEntry("padding-" + i + ".txt"));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to build the padded test archive", e);
        }
        return packed.toByteArray();
    }

    // One recognised member whose DECOMPRESSED size passes the per-member cap, written in chunks rather than as one string: the compressed archive
    // is a few dozen KB, which is exactly the shape the cap exists to catch.
    private static byte[] oversizedArchive() {
        final byte[] chunk = new byte[CHUNK_BYTES];
        Arrays.fill(chunk, (byte) 'a');

        final ByteArrayOutputStream packed = new ByteArrayOutputStream();
        try (final ZipOutputStream archive = new ZipOutputStream(packed, StandardCharsets.UTF_8)) {
            archive.putNextEntry(new ZipEntry(ACTIONS_FILE));
            for (int written = 0; written <= TransferArchive.MAX_MEMBER_BYTES; written += CHUNK_BYTES) {
                archive.write(chunk);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException("Unable to build the oversized test archive", e);
        }
        return packed.toByteArray();
    }
}
