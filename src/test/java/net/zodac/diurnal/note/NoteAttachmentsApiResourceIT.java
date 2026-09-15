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
import static net.zodac.diurnal.http.HttpStatusCodes.NO_CONTENT;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
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
 * The public note-attachment API: listing a day's files, uploading one as a raw body, downloading it, renaming it and removing it. The rules
 * themselves live in {@link NoteAttachmentService} and are shared with the web surface; this pins the JSON translation and the status codes, which
 * are the half that differs — a refusal is a {@code 400} here where the page answers {@code 422}, and an attachment the account does not have is a
 * {@code 404} rather than either.
 */
@QuarkusTest
@TestSecurity(user = NoteAttachmentsApiResourceIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NoteAttachmentsApiResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "note-attachments-api-it@lt.test";

    private static final LocalDate DAY = FIXED_TODAY;
    private static final String DAY_PATH = "/api/v1/notes/" + FIXED_TODAY + "/attachments";
    private static final byte[] FILE = "not really a png".getBytes(StandardCharsets.UTF_8);

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Note Attachments API User").id;
    }

    // ── list ──────────────────────────────────────────────────────────────────

    @Test
    void list_forDayWithNoFiles_isEmptyListRatherThanNotFound() {
        given().get(DAY_PATH)
            .then().statusCode(OK)
            .body("date", Matchers.equalTo(DAY.toString()))
            .body("attachments.size()", Matchers.is(0));
    }

    @Test
    void list_carriesBothNamesOfARenamedFile() {
        runInTx(() -> newRenamedAttachment(userId, DAY, "Berlin ticket", "ticket-stub.png", FILE));

        given().get(DAY_PATH)
            .then().statusCode(OK)
            .body("attachments[0].name", Matchers.equalTo("Berlin ticket"))
            .body("attachments[0].fileName", Matchers.equalTo("ticket-stub.png"));
    }

    @Test
    void list_carriesTheNameSizeAndTokenButNotTheBytes() {
        runInTx(() -> newAttachment(userId, DAY, "route.png", FILE));

        given().get(DAY_PATH)
            .then().statusCode(OK)
            .body("attachments[0].name", Matchers.equalTo("route.png"))
            .body("attachments[0].byteSize", Matchers.is(FILE.length))
            .body("attachments[0].preview", Matchers.equalTo("image"))
            .body("attachments[0].token", Matchers.equalTo("[[route.png]]"));
    }

    @Test
    void list_withMalformedDate_isBadRequest() {
        given().get("/api/v1/notes/not-a-date/attachments")
            .then().statusCode(BAD_REQUEST);
    }

    @Test
    void theNoteEndpointsAreStillReachableBesideTheNestedResource() {
        runInTx(() -> newNote(userId, DAY, "Ran 5k"));

        // The attachments resource is rooted at /api/v1/notes/{date}/attachments, one literal deeper than the notes resource's own /{date} - so
        // this is what proves a day is still read as a day rather than being swallowed by the nested path.
        given().get("/api/v1/notes/" + DAY)
            .then().statusCode(OK)
            .body("content", Matchers.equalTo("Ran 5k"));
    }

    // ── upload ────────────────────────────────────────────────────────────────

    @Test
    void attach_storesTheFileAndAnswersTheTokenToEmbed() {
        given().contentType("image/png")
            .queryParam("filename", "route.png")
            .body(FILE)
            .post(DAY_PATH)
            .then().statusCode(OK)
            .body("name", Matchers.equalTo("route.png"))
            .body("fileName", Matchers.equalTo("route.png"))
            .body("byteSize", Matchers.is(FILE.length))
            .body("token", Matchers.equalTo("[[route.png]]"));
    }

    @Test
    void attach_keepsTheUploadedNameEvenWhenTheDisplayNameIsDeduplicated() {
        attach("route.png");

        given().contentType("image/png")
            .queryParam("filename", "route.png")
            .body(FILE)
            .post(DAY_PATH)
            .then().statusCode(OK)
            // The DISPLAY name is what has to be unique, because the note's token addresses it. Two uploads of one filename on a day are a real
            // thing that happened, and the file column reports it truthfully.
            .body("name", Matchers.equalTo("route (2).png"))
            .body("fileName", Matchers.equalTo("route.png"));
    }

    @Test
    void attach_refusesEmptyBodyWithBadRequest() {
        given().contentType("image/png")
            .queryParam("filename", "empty.png")
            .body(new byte[0])
            .post(DAY_PATH)
            .then().statusCode(BAD_REQUEST)
            .body("message", Matchers.not(Matchers.emptyOrNullString()));
    }

    @Test
    void attach_withMalformedDate_isBadRequest() {
        given().contentType("image/png")
            .queryParam("filename", "route.png")
            .body(FILE)
            .post("/api/v1/notes/not-a-date/attachments")
            .then().statusCode(BAD_REQUEST);
    }

    // ── download ──────────────────────────────────────────────────────────────

    @Test
    void download_servesTheBytesUnderTheStoredName() {
        final UUID id = attach("route.png");

        final byte[] body = given().get(DAY_PATH + '/' + id)
            .then().statusCode(OK)
            .contentType("image/png")
            .extract().asByteArray();

        assertThat(body)
            .as("the file comes back byte-identical, which is the whole of what a download owes its caller")
            .isEqualTo(FILE);
    }

    @Test
    void download_ofAnUnknownAttachment_isNotFound() {
        given().get(DAY_PATH + '/' + UUID.randomUUID())
            .then().statusCode(NOT_FOUND);
    }

    // ── rename ────────────────────────────────────────────────────────────────

    @Test
    void rename_rewritesTheNameAndTheNotesOwnToken() {
        final UUID id = attach("route.png");
        runInTx(() -> newNote(userId, DAY, "Ran 5k.\n[[route.png]]"));

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"Berlin route.png\"}")
            .patch(DAY_PATH + '/' + id)
            .then().statusCode(OK)
            .body("name", Matchers.equalTo("Berlin route.png"))
            .body("token", Matchers.equalTo("[[Berlin route.png]]"));

        runInTx(() -> assertThat(storedNoteContent(userId, DAY))
            .as("the note's token is rewritten in the same transaction, so the writing and the file never disagree")
            .isEqualTo("Ran 5k.\n[[Berlin route.png]]"));
    }

    @Test
    void rename_answersWithTheUploadedFileNameUnchanged() {
        final UUID id = attach("route.png");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"Berlin route\"}")
            .patch(DAY_PATH + '/' + id)
            .then().statusCode(OK)
            .body("name", Matchers.equalTo("Berlin route"))
            .body("fileName", Matchers.equalTo("route.png"))
            // Still previewable: what the bytes are is decided by the file name, which the rename did not touch.
            .body("preview", Matchers.equalTo("image"));
    }

    @Test
    void rename_toNameTheDayAlreadyHas_isBadRequest() {
        attach("route.png");
        final UUID second = attach("map.png");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"route.png\"}")
            .patch(DAY_PATH + '/' + second)
            .then().statusCode(BAD_REQUEST)
            .body("message", Matchers.not(Matchers.emptyOrNullString()));
    }

    @Test
    void rename_ofAnUnknownAttachment_isNotFound() {
        given().contentType(ContentType.JSON)
            .body("{\"name\":\"anything.png\"}")
            .patch(DAY_PATH + '/' + UUID.randomUUID())
            .then().statusCode(NOT_FOUND);
    }

    @Test
    void rename_withBlankName_isBadRequest() {
        final UUID id = attach("route.png");

        given().contentType(ContentType.JSON)
            .body("{\"name\":\"   \"}")
            .patch(DAY_PATH + '/' + id)
            .then().statusCode(BAD_REQUEST);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void delete_removesTheFileAndItsTokenAndAnswersNoContent() {
        final UUID id = attach("route.png");
        runInTx(() -> newNote(userId, DAY, "Ran 5k. [[route.png]] Felt good."));

        given().delete(DAY_PATH + '/' + id)
            .then().statusCode(NO_CONTENT);

        runInTx(() -> {
            assertThat(NoteAttachment.sealedById(userId, id))
                .as("the row is gone")
                .isNull();
            assertThat(storedNoteContent(userId, DAY))
                .as("and so is the token; the note's own normalisation then closes the gap it left, as it would for any other save")
                .isEqualTo("Ran 5k. Felt good.");
        });
    }

    @Test
    void delete_ofAnUnknownAttachment_isNotFound() {
        given().delete(DAY_PATH + '/' + UUID.randomUUID())
            .then().statusCode(NOT_FOUND);
    }

    @Test
    void delete_ofAnAttachmentOnAnotherDay_isNotFound() {
        final UUID id = attach("route.png");

        given().delete("/api/v1/notes/" + DAY.plusDays(1) + "/attachments/" + id)
            .then().statusCode(NOT_FOUND);
    }

    private UUID attach(final String name) {
        final UUID[] id = new UUID[1];
        runInTx(() -> id[0] = newAttachment(userId, DAY, name, FILE));
        return id[0];
    }
}
