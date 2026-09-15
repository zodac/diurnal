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
import static net.zodac.diurnal.http.HttpStatusCodes.NOT_FOUND;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static net.zodac.diurnal.http.HttpStatusCodes.UNPROCESSABLE_ENTITY;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * The note box's internal attachment endpoints: the per-day listing it reads, the raw-body upload the drop zone posts to, the rename and delete the
 * hover card offers, and the bytes its preview and download link ask for.
 *
 * <p>
 * The two halves that matter most here are the ones no unit test can reach: that a write to an attachment also rewrites the day's NOTE in the same
 * transaction, and that the media type a file is served with is derived from its stored name rather than from whatever the uploader sent.
 */
@QuarkusTest
@TestSecurity(user = NoteAttachmentsInternalResourceIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NoteAttachmentsInternalResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "note-attachments-internal-it@lt.test";

    private static final LocalDate DAY = FIXED_TODAY;
    private static final String DAY_PATH = "/internal/note-attachments/" + FIXED_TODAY;
    private static final byte[] FILE = "not really a png".getBytes(StandardCharsets.UTF_8);

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Note Attachments User").id;
    }

    // ── the notes page's attachments table ────────────────────────────────────

    @Test
    void table_isHtmlRatherThanTheDayListingForTheLiteralPath() {
        given().get("/internal/note-attachments/list")
            .then().statusCode(OK)
            .contentType(Matchers.startsWith("text/html"));
    }

    @Test
    void table_withNoAttachments_rendersTheEmptyRow() {
        final String html = given().get("/internal/note-attachments/list")
            .then().statusCode(OK)
            .extract().asString();

        assertThat(html)
            .as("an account with no file at all is pointed at the dashboard, which is the only place one can be attached")
            .contains("note-attachments-empty-row")
            .contains("No attachments yet");
    }

    @Test
    void table_rendersARowPerFileWithItsDayAndSize() {
        runInTx(() -> newAttachment(userId, DAY, "route.png", FILE));

        final String html = given().get("/internal/note-attachments/list")
            .then().statusCode(OK)
            .extract().asString();

        assertThat(html)
            .as("the row carries the name, the file's own URL and its size rounded up to a whole kilobyte")
            .contains("route.png")
            .contains("/file")
            .contains("1 KB");
    }

    @Test
    void table_showsBothNamesForARenamedFile() {
        runInTx(() -> newRenamedAttachment(userId, DAY, "Berlin ticket", "ticket-stub.png", FILE));

        final String html = given().get("/internal/note-attachments/list")
            .then().statusCode(OK)
            .extract().asString();

        assertThat(html)
            .as("the row carries what the note calls the file AND what the file is, which is the whole point of the second column")
            .contains("Berlin ticket")
            .contains("ticket-stub.png");
    }

    @Test
    void table_narrowsToTheFilesWhoseUploadedNameMatches() {
        runInTx(() -> {
            newRenamedAttachment(userId, DAY, "Berlin ticket", "ticket-stub.png", FILE);
            newRenamedAttachment(userId, DAY, "receipt.pdf", "receipt.pdf", FILE);
        });

        final String html = given().queryParam("q", "STUB")
            .get("/internal/note-attachments/list")
            .then().statusCode(OK)
            .extract().asString();

        assertThat(html)
            .as("a file renamed since it was uploaded must still be findable by what it was called")
            .contains("Berlin ticket")
            .doesNotContain("receipt.pdf");
    }

    @Test
    void table_narrowsToTheFilesWhoseNameMatches() {
        runInTx(() -> {
            newAttachment(userId, DAY, "Berlin route.png", FILE);
            newAttachment(userId, DAY, "receipt.pdf", FILE);
        });

        final String html = given().queryParam("q", "ROUTE")
            .get("/internal/note-attachments/list")
            .then().statusCode(OK)
            .extract().asString();

        assertThat(html)
            .as("the table searches filenames, case-insensitively and as a plain substring - and MARKS the run it matched, which is why the name "
                + "is no longer contiguous in the rendered row")
            .contains("Berlin <mark class=\"note-mark\">route</mark>.png")
            .doesNotContain("receipt.pdf");
    }

    @Test
    void table_withATermMatchingNeitherName_findsNothing() {
        runInTx(() -> newRenamedAttachment(userId, DAY, "Berlin ticket", "ticket-stub.png", FILE));

        final String html = given().queryParam("q", "receipt")
            .get("/internal/note-attachments/list")
            .then().statusCode(OK)
            .extract().asString();

        assertThat(html)
            .as("matching EITHER name must not turn into matching anything")
            .contains("note-attachments-empty-row");
    }

    @Test
    void table_withATermThatMatchesNothing_saysSoRatherThanOfferingTheDashboard() {
        runInTx(() -> newAttachment(userId, DAY, "route.png", FILE));

        final String html = given().queryParam("q", "nothing here")
            .get("/internal/note-attachments/list")
            .then().statusCode(OK)
            .extract().asString();

        assertThat(html)
            .as("a search that found nothing is a different state from an account holding nothing")
            .contains("No attachments match your search.");
    }

    @Test
    void table_clampsAnOutOfRangePageRatherThanRejectingIt() {
        runInTx(() -> newAttachment(userId, DAY, "route.png", FILE));

        given().queryParam("page", 9)
            .get("/internal/note-attachments/list")
            .then().statusCode(OK);
    }

    // ── the day listing ───────────────────────────────────────────────────────

    @Test
    void listing_forDayWithNoFiles_isEmpty() {
        given().get(DAY_PATH)
            .then().statusCode(OK)
            .body("date", Matchers.equalTo(DAY.toString()))
            .body("attachments.size()", Matchers.is(0));
    }

    @Test
    void listing_carriesEverythingTheNoteBoxDraws() {
        runInTx(() -> newAttachment(userId, DAY, "route.png", FILE));

        given().get(DAY_PATH)
            .then().statusCode(OK)
            .body("attachments[0].name", Matchers.equalTo("route.png"))
            .body("attachments[0].byteSize", Matchers.is(FILE.length))
            .body("attachments[0].preview", Matchers.equalTo("image"))
            .body("attachments[0].token", Matchers.equalTo("[[route.png]]"))
            .body("attachments[0].url", Matchers.containsString("/internal/note-attachments/" + DAY));
    }

    @Test
    void listing_rejectsNonDate() {
        given().get("/internal/note-attachments/not-a-date")
            .then().statusCode(BAD_REQUEST);
    }

    // ── upload ────────────────────────────────────────────────────────────────

    @Test
    void upload_storesTheFileAndAnswersTheTokenToEmbed() {
        given().contentType("image/png")
            .queryParam("filename", "route.png")
            .body(FILE)
            .post(DAY_PATH)
            .then().statusCode(OK)
            .body("date", Matchers.equalTo(DAY.toString()))
            .body("attachment.name", Matchers.equalTo("route.png"))
            .body("attachment.token", Matchers.equalTo("[[route.png]]"))
            // An upload deliberately writes no note: the token goes in at the caret and reaches the server on the next save, so replacing the
            // box's own half-typed text here would discard it.
            .body("noteContent", Matchers.nullValue());
    }

    @Test
    void upload_sanitisesTheNameItWasGiven() {
        given().contentType("image/png")
            .queryParam("filename", "C:\\Users\\me\\photo[1].png")
            .body(FILE)
            .post(DAY_PATH)
            .then().statusCode(OK)
            .body("attachment.name", Matchers.equalTo("photo_1_.png"));
    }

    @Test
    void upload_resolvesCollisionOnTheSameDay() {
        runInTx(() -> newAttachment(userId, DAY, "route.png", FILE));

        given().contentType("image/png")
            .queryParam("filename", "route.png")
            .body(FILE)
            .post(DAY_PATH)
            .then().statusCode(OK)
            .body("attachment.name", Matchers.equalTo("route (2).png"));
    }

    @Test
    void upload_refusesAnEmptyFile() {
        given().contentType("image/png")
            .queryParam("filename", "empty.png")
            .body(new byte[0])
            .post(DAY_PATH)
            .then().statusCode(UNPROCESSABLE_ENTITY)
            .body("message", Matchers.not(Matchers.emptyOrNullString()));
    }

    @Test
    void upload_worksForFutureDay() {
        // A note may be written for any day, so a file may be attached to one too - there is no future-date guard on this path either.
        given().contentType("image/png")
            .queryParam("filename", "plan.png")
            .body(FILE)
            .post("/internal/note-attachments/" + DAY.plusMonths(1))
            .then().statusCode(OK)
            .body("attachment.name", Matchers.equalTo("plan.png"));
    }

    // ── rename ────────────────────────────────────────────────────────────────

    @Test
    void rename_rewritesTheStoredNameAndTheNotesOwnToken() {
        final UUID id = attach("route.png");
        runInTx(() -> newNote(userId, DAY, "Ran 5k.\n[[route.png]]"));

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"Berlin route.png\"}")
            .post(DAY_PATH + '/' + id + "/rename")
            .then().statusCode(OK)
            .body("attachment.name", Matchers.equalTo("Berlin route.png"))
            .body("noteContent", Matchers.equalTo("Ran 5k.\n[[Berlin route.png]]"));

        runInTx(() -> assertThat(storedAttachmentName(userId, DAY, id))
            .as("the stored name and the note's own token are rewritten in one transaction, so they cannot disagree")
            .isEqualTo("Berlin route.png"));
    }

    @Test
    void rename_leavesTheUploadedFileNameAlone() {
        final UUID id = attach("route.png");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"Berlin route\"}")
            .post(DAY_PATH + '/' + id + "/rename")
            .then().statusCode(OK)
            .body("attachment.name", Matchers.equalTo("Berlin route"))
            .body("attachment.fileName", Matchers.equalTo("route.png"));

        runInTx(() -> assertThat(storedAttachmentFileName(userId, DAY, id))
            .as("a rename relabels what the note calls the file; what the file IS is not the user's to change")
            .isEqualTo("route.png"));
    }

    @Test
    void rename_leavesDayWithNoNoteAlone() {
        final UUID id = attach("route.png");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"Berlin route.png\"}")
            .post(DAY_PATH + '/' + id + "/rename")
            .then().statusCode(OK)
            .body("noteContent", Matchers.equalTo(""));
    }

    @Test
    void rename_refusesNameAnotherFileOnTheDayAlreadyHas() {
        attach("route.png");
        final UUID second = attach("map.png");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"route.png\"}")
            .post(DAY_PATH + '/' + second + "/rename")
            .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(storedAttachmentName(userId, DAY, second))
            .as("a refused rename changes nothing - the token addresses a file BY name, so two files cannot share one")
            .isEqualTo("map.png"));
    }

    @Test
    void rename_refusesSquareBracket() {
        final UUID id = attach("route.png");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"route[1].png\"}")
            .post(DAY_PATH + '/' + id + "/rename")
            .then().statusCode(UNPROCESSABLE_ENTITY)
            // A TYPED name is refused where an uploaded one is coerced: the name is the whole of what was submitted.
            .body("message", Matchers.not(Matchers.emptyOrNullString()));
    }

    @Test
    void rename_refusesAnAttachmentOnAnotherDay() {
        final UUID id = attach("route.png");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"other.png\"}")
            .post("/internal/note-attachments/" + DAY.plusDays(1) + '/' + id + "/rename")
            .then().statusCode(UNPROCESSABLE_ENTITY);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removesTheFileAndItsTokenFromTheNote() {
        final UUID id = attach("route.png");
        runInTx(() -> newNote(userId, DAY, "Ran 5k. [[route.png]] Felt good."));

        given().post(DAY_PATH + '/' + id + "/delete")
            .then().statusCode(OK)
            // The token goes and nothing else is touched - but the note is then stored through NoteService like any other save, so the note's own
            // normalisation closes the gap it left, exactly as it would for a space the user deleted by hand.
            .body("noteContent", Matchers.equalTo("Ran 5k. Felt good."));

        runInTx(() -> assertThat(NoteAttachment.sealedById(userId, id))
            .as("the row is gone as well as the token")
            .isNull());
    }

    @Test
    void delete_ofNoteThatBecomesEmpty_removesTheNoteToo() {
        final UUID id = attach("route.png");
        runInTx(() -> newNote(userId, DAY, "[[route.png]]"));

        given().post(DAY_PATH + '/' + id + "/delete")
            .then().statusCode(OK)
            .body("noteContent", Matchers.equalTo(""));

        runInTx(() -> assertThat(storedNoteContent(userId, DAY))
            .as("a note that was nothing but its attachment is an empty note, and an empty note is no row")
            .isNull());
    }

    @Test
    void delete_refusesAnUnknownAttachment() {
        given().post(DAY_PATH + '/' + UUID.randomUUID() + "/delete")
            .then().statusCode(UNPROCESSABLE_ENTITY);
    }

    // ── the file itself ───────────────────────────────────────────────────────

    @Test
    void file_servesAnImageInlineAsItsOwnType() {
        final UUID id = attach("route.png");

        given().get(DAY_PATH + '/' + id + "/file")
            .then().statusCode(OK)
            .contentType("image/png")
            .header("Content-Disposition", Matchers.startsWith("inline; "));
    }

    @Test
    void file_servesEverythingElseAsOpaqueBytesToDownload() {
        final UUID id = attach("page.html");

        given().get(DAY_PATH + '/' + id + "/file")
            .then().statusCode(OK)
            // Derived from the stored NAME, never from the Content-Type the upload carried - otherwise an account could store a file this
            // application then served, from its own origin, as whatever the uploader said it was.
            .contentType("application/octet-stream")
            .header("Content-Disposition", Matchers.startsWith("attachment; "));
    }

    @Test
    void file_typeSurvivesARenameThatDropsTheExtension() {
        runInTx(() -> newRenamedAttachment(userId, DAY, "Berlin ticket", "ticket-stub.png", FILE));
        final UUID id = UUID.fromString(given().get(DAY_PATH).then().statusCode(OK).extract().path("attachments[0].id"));

        given().get(DAY_PATH + '/' + id + "/file")
            .then().statusCode(OK)
            // From the FILE name, not the display name: a user may rename a file to anything, and relabelling a PNG does not stop it being one -
            // which decides both the type and whether it renders. The name it SAVES under is the display name, which is what they chose to call it.
            .contentType("image/png")
            .header("Content-Disposition", Matchers.startsWith("inline; "))
            .header("Content-Disposition", Matchers.containsString("Berlin ticket"));
    }

    @Test
    void listing_carriesBothNames() {
        runInTx(() -> newRenamedAttachment(userId, DAY, "Berlin ticket", "ticket-stub.png", FILE));

        given().get(DAY_PATH)
            .then().statusCode(OK)
            .body("attachments[0].name", Matchers.equalTo("Berlin ticket"))
            .body("attachments[0].fileName", Matchers.equalTo("ticket-stub.png"))
            .body("attachments[0].preview", Matchers.equalTo("image"));
    }

    @Test
    void file_servesASoundFileInlineAsItsOwnType() {
        final UUID id = attach("memo.mp3");

        given().get(DAY_PATH + '/' + id + "/file")
            .then().statusCode(OK)
            // Audio joins images in being served as what it is: a media element decodes, it does not execute, so there is nothing here of the
            // same-origin script risk that keeps .svg and .html opaque.
            .contentType("audio/mpeg")
            .header("Content-Disposition", Matchers.startsWith("inline; "));
    }

    @Test
    void listing_reportsASoundFileAsPlayable() {
        runInTx(() -> newAttachment(userId, DAY, "memo.opus", FILE));

        given().get(DAY_PATH)
            .then().statusCode(OK)
            .body("attachments[0].preview", Matchers.equalTo("audio"));
    }

    @Test
    void listing_reportsAnUnplayableFileAsHavingNoPreview() {
        runInTx(() -> newAttachment(userId, DAY, "notes.pdf", FILE));

        given().get(DAY_PATH)
            .then().statusCode(OK)
            .body("attachments[0].preview", Matchers.equalTo("none"));
    }

    @Test
    void file_isNotFoundForAnAttachmentOnAnotherDay() {
        final UUID id = attach("route.png");

        given().get("/internal/note-attachments/" + DAY.plusDays(1) + '/' + id + "/file")
            .then().statusCode(NOT_FOUND);
    }

    @Test
    void file_isNotFoundForAnUnknownAttachment() {
        given().get(DAY_PATH + '/' + UUID.randomUUID() + "/file")
            .then().statusCode(NOT_FOUND);
    }

    // ── the note's own save collects what it no longer names ──────────────────

    @Test
    void savingNoteWithoutItsToken_removesTheAttachment() {
        final UUID kept = attach("kept.png");
        final UUID dropped = attach("dropped.png");

        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Still here: [[kept.png]]\"}")
            .post("/internal/notes/" + DAY)
            .then().statusCode(OK);

        runInTx(() -> {
            assertThat(NoteAttachment.sealedById(userId, kept))
                .as("a file the saved note still names stays")
                .isNotNull();
            assertThat(NoteAttachment.sealedById(userId, dropped))
                .as("and one it no longer names goes - deleting the token is how a file is removed by editing")
                .isNull();
        });
    }

    @Test
    void clearingDaysNote_takesItsAttachmentsWithIt() {
        final UUID id = attach("route.png");
        runInTx(() -> newNote(userId, DAY, "[[route.png]]"));

        given().post("/internal/notes/" + DAY + "/delete")
            .then().statusCode(OK);

        runInTx(() -> assertThat(NoteAttachment.sealedById(userId, id))
            .as("an attachment is embedded IN the writing, so a day with no writing has nothing to embed one")
            .isNull());
    }

    private UUID attach(final String name) {
        final UUID[] id = new UUID[1];
        runInTx(() -> id[0] = newAttachment(userId, DAY, name, FILE));
        return id[0];
    }
}
