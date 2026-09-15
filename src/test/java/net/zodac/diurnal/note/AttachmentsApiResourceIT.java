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

package net.zodac.diurnal.note;

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.BAD_REQUEST;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * The public account-wide attachment API: every file the user holds, newest day first, searched by NAME. The selection rule lives in
 * {@link NoteAttachmentService} and is shared with the notes page's attachments table; what is pinned here is the ordering the API publishes, that
 * the search matches on the filename rather than on the note it sits in, and the surface policy that an out-of-range page is rejected.
 */
@QuarkusTest
@TestSecurity(user = AttachmentsApiResourceIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class AttachmentsApiResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "attachments-api-it@lt.test";

    private static final String PATH = "/api/v1/attachments";
    private static final LocalDate DAY = FIXED_TODAY;
    private static final byte[] FILE = "not really a png".getBytes(StandardCharsets.UTF_8);

    private UUID userId;
    private UUID otherId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Attachments API User").id;
        otherId = newUser("attachments-api-other@lt.test", "Attachments API Other").id;
    }

    @Test
    void list_withNoAttachments_isAnEmptyFirstPage() {
        given().get(PATH)
            .then().statusCode(OK)
            .body("items.size()", Matchers.is(0))
            .body("totalCount", Matchers.is(0))
            .body("totalPages", Matchers.is(0))
            .body("currentPage", Matchers.is(1));
    }

    @Test
    void list_carriesEverythingAClientNeedsToRenderAndEmbedTheFile() {
        runInTx(() -> newAttachment(userId, DAY, "route.png", FILE));

        given().get(PATH)
            .then().statusCode(OK)
            .body("items[0].date", Matchers.equalTo(DAY.toString()))
            .body("items[0].name", Matchers.equalTo("route.png"))
            .body("items[0].byteSize", Matchers.is(FILE.length))
            .body("items[0].preview", Matchers.equalTo("image"))
            .body("items[0].token", Matchers.equalTo("[[route.png]]"));
    }

    @Test
    void list_ordersNewestDayFirstAndOldestAttachedFirstWithinADay() {
        runInTx(() -> {
            newAttachment(userId, DAY.minusDays(2), "older.png", FILE);
            newAttachment(userId, DAY, "first.png", FILE);
            newAttachment(userId, DAY, "second.png", FILE);
        });

        given().get(PATH)
            .then().statusCode(OK)
            .body("items.name", Matchers.contains("first.png", "second.png", "older.png"));
    }

    @Test
    void list_holdsNothingOfAnotherAccounts() {
        runInTx(() -> newAttachment(otherId, DAY, "theirs.png", FILE));

        given().get(PATH)
            .then().statusCode(OK)
            .body("totalCount", Matchers.is(0));
    }

    @Test
    void search_matchesTheFilenameCaseInsensitivelyAsASubstring() {
        runInTx(() -> {
            newAttachment(userId, DAY, "Berlin route.png", FILE);
            newAttachment(userId, DAY, "receipt.pdf", FILE);
        });

        given().queryParam("q", "ROUTE")
            .get(PATH)
            .then().statusCode(OK)
            .body("totalCount", Matchers.is(1))
            .body("items[0].name", Matchers.equalTo("Berlin route.png"));
    }

    @Test
    void list_carriesBothNames() {
        runInTx(() -> newRenamedAttachment(userId, DAY, "Berlin ticket", "ticket-stub.png", FILE));

        given().get(PATH)
            .then().statusCode(OK)
            .body("items[0].name", Matchers.equalTo("Berlin ticket"))
            .body("items[0].fileName", Matchers.equalTo("ticket-stub.png"))
            .body("items[0].token", Matchers.equalTo("[[Berlin ticket]]"));
    }

    @Test
    void search_matchesTheUploadedFileNameToo() {
        runInTx(() -> {
            newRenamedAttachment(userId, DAY, "Berlin ticket", "ticket-stub.png", FILE);
            newRenamedAttachment(userId, DAY, "receipt.pdf", "receipt.pdf", FILE);
        });

        given().queryParam("q", "stub")
            .get(PATH)
            .then().statusCode(OK)
            .body("totalCount", Matchers.is(1))
            .body("items[0].name", Matchers.equalTo("Berlin ticket"));
    }

    @Test
    void search_countsARowOnceWhenBothNamesMatch() {
        runInTx(() -> newRenamedAttachment(userId, DAY, "route.png", "route.png", FILE));

        given().queryParam("q", "route")
            .get(PATH)
            .then().statusCode(OK)
            .body("totalCount", Matchers.is(1));
    }

    @Test
    void search_doesNotMatchOnTheNotesOwnText() {
        runInTx(() -> {
            newNote(userId, DAY, "A day in Berlin. [[route.png]]");
            newAttachment(userId, DAY, "route.png", FILE);
        });

        given().queryParam("q", "Berlin")
            .get(PATH)
            .then().statusCode(OK)
            .body("totalCount", Matchers.is(0));
    }

    @Test
    void search_thatMatchesNothing_isAnEmptyFirstPage() {
        runInTx(() -> newAttachment(userId, DAY, "route.png", FILE));

        given().queryParam("q", "nothing here")
            .get(PATH)
            .then().statusCode(OK)
            .body("totalCount", Matchers.is(0))
            .body("currentPage", Matchers.is(1));
    }

    @Test
    void list_pagesByThePageSizePreference() {
        runInTx(() -> {
            for (int i = 0; i < 7; i++) {
                newAttachment(userId, DAY.minusDays(i), "file-" + i + ".png", FILE);
            }
        });

        given().queryParam("page", 2)
            .get(PATH)
            .then().statusCode(OK)
            .body("totalCount", Matchers.is(7))
            .body("totalPages", Matchers.is(2))
            .body("currentPage", Matchers.is(2))
            .body("items.size()", Matchers.is(2));
    }

    @Test
    void list_rejectsAnOutOfRangePageRatherThanClampingIt() {
        runInTx(() -> newAttachment(userId, DAY, "route.png", FILE));

        given().queryParam("page", 9)
            .get(PATH)
            .then().statusCode(BAD_REQUEST);
    }
}
