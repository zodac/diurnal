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
import static net.zodac.diurnal.http.HttpStatusCodes.UNPROCESSABLE_ENTITY;
import static net.zodac.diurnal.transfer.TransferFiles.ACTIONS_FILE;
import static net.zodac.diurnal.transfer.TransferFiles.LOGS_FILE;
import static net.zodac.diurnal.transfer.TransferFiles.NOTES_FILE;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.time.Instant;
import java.util.Map;
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
            .contains("The archive does not contain <strong>" + NOTES_FILE + "</strong>.");
        assertThat(panel)
            .as("a problem with the archive as a whole belongs to no member, so its row leads with neither a file name nor the dash after one")
            .doesNotContain("&mdash;");
    }

    @Test
    void importData_rowProblem_leadsWithItsMemberAndEscapesTheArchivesOwnTextExactlyOnce() {
        // The panel renders a reason as MARKUP (it is already-rendered HTML, not a value - see partials/import-panel.html), so the archive's own
        // text must arrive from partials/import-reason.html already escaped: escaped there once, and not again here.
        final String panel = importPanel(archiveOf(
            "name,colour\r\nSwimming,#22c55e\r\n",
            "date,action,count\r\n2026-06-14,<script>alert(1)</script>,3\r\n",
            "date,content\r\n"));

        assertThat(panel)
            .as("a problem INSIDE a member still leads with that member's name and the line to correct")
            .contains("<strong>" + LOGS_FILE + "</strong>, line 2");
        assertThat(panel)
            .as("a name out of the uploaded file must never reach the page as markup")
            .doesNotContain("<script>alert(1)</script>")
            .contains("&lt;script&gt;")
            .doesNotContain("&amp;lt;");
        assertThat(panel)
            .as("the CSV file the sentence names is bolded, the same as a missing member's is")
            .contains("is defined in <strong>" + ACTIONS_FILE + "</strong>.");
    }

    @Test
    void importData_wrongHeader_chipsEachColumnNameButNotTheCommasBetweenThem() {
        final String panel = importPanel(archiveOf(
            "name,color\r\n",
            "date,action,count\r\n",
            "date,content\r\n"));

        assertThat(panel)
            .as("a chip marks one column name, so the commas separating them stay plain sentence text - and the chips have to survive as markup")
            .contains("The header row must be exactly <code>name</code>, <code>colour</code>.");
    }

    private static String importPanel(final byte[] archive) {
        return given().contentType(APPLICATION_ZIP).body(archive)
            .post(IMPORT_PATH)
            .then().statusCode(UNPROCESSABLE_ENTITY)
            .extract().body().asString();
    }

    private static byte[] archiveOf(final String actions, final String logs, final String notes) {
        return TransferArchive.pack(Map.of(ACTIONS_FILE, actions, LOGS_FILE, logs, NOTES_FILE, notes), Instant.now());
    }
}
