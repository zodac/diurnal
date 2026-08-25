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
import static net.zodac.diurnal.transfer.TransferFiles.ACTIONS_FILE;
import static net.zodac.diurnal.transfer.TransferFiles.ALL_FILES;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * A deployment that has answered {@code EXPORT_CSV_BOM=false}: the export writes plain UTF-8, and the archive it produces still imports.
 *
 * <p>
 * The default (mark present) is pinned by {@link TransferApiResourceIT#export_writesEveryMemberWithItsHeader()}, and the write itself by
 * {@code CsvTest}; what only an {@code @QuarkusTest} can show is that the setting reaches {@link ExportService} at all - a config key nothing reads
 * is the failure mode this exists for.
 */
@QuarkusTest
@TestProfile(CsvBomDisabledProfile.class)
@TestSecurity(user = CsvBomDisabledIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class CsvBomDisabledIT extends IntegrationTestBase {

    static final String PRIMARY = "csv-bom-it@lt.test";

    private static final char BYTE_ORDER_MARK = '﻿';
    private static final String EXPORT_PATH = "/api/v1/data/export";
    private static final String IMPORT_PATH = "/api/v1/data/import";
    private static final String APPLICATION_ZIP = "application/zip";

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Bom User").id;
    }

    @Test
    void export_writesNoByteOrderMark_andTheArchiveStillImports() {
        runInTx(() -> {
            final Action action = newAction(userId, "Sauna");
            newLog(userId, action.id, LocalDate.of(2026, 6, 14), 2);
            newNote(userId, LocalDate.of(2026, 6, 14), "Café, très chaud");
        });

        final byte[] archive = given().get(EXPORT_PATH).then().statusCode(OK).extract().asByteArray();
        final Map<String, String> members = unpack(archive);

        assertThat(members)
            .as("the archive still holds all three members - only the leading mark is gone")
            .containsOnlyKeys(ALL_FILES);
        for (final String member : ALL_FILES) {
            assertThat(members.getOrDefault(member, ""))
                .as("%s must start with its header row, not with a byte-order mark", member)
                .doesNotStartWith(String.valueOf(BYTE_ORDER_MARK));
        }
        assertThat(members.getOrDefault(ACTIONS_FILE, ""))
            .as("the member is otherwise exactly what it always was")
            .isEqualTo("name,colour\r\nSauna,#6366f1\r\n");

        // The setting is write-side only, so what it produced must still be an archive this same app accepts.
        given().contentType(APPLICATION_ZIP).body(archive)
            .post(IMPORT_PATH)
            .then().statusCode(OK);

        assertThat(storedNoteContent(userId, LocalDate.of(2026, 6, 14)))
            .as("a BOM-less export re-imported must land the non-ASCII content it carried, unchanged")
            .isEqualTo("Café, très chaud");
    }

    private static Map<String, String> unpack(final byte[] archive) {
        final ArchiveOutcome outcome = TransferArchive.unpack(archive);
        assertThat(outcome)
            .as("the exported archive must be readable")
            .isInstanceOf(ArchiveOutcome.Unpacked.class);
        return outcome instanceof ArchiveOutcome.Unpacked(final Map<String, String> members) ? members : Map.of();
    }
}
