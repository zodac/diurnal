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
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * The dashboard's internal notes endpoints: the date-range feed that fills the browser's per-month cache (and paints the green day numbers), plus the
 * save and clear the note box posts to.
 */
@QuarkusTest
@TestSecurity(user = NotesInternalResourceIT.PRIMARY, roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NotesInternalResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "notes-internal-it@lt.test";

    private static final LocalDate DAY = FIXED_TODAY;
    private static final String DAY_PATH = "/internal/notes/" + FIXED_TODAY;

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Notes Internal User").id;
    }

    // ── the range feed ────────────────────────────────────────────────────────

    @Test
    void feed_returnsOnlyTheDaysWithANote_keyedByDate() {
        runInTx(() -> {
            newNote(userId, DAY.minusDays(1), "Yesterday");
            newNote(userId, DAY, "Today");
        });

        given().queryParam("start", DAY.minusDays(2).toString())
            .queryParam("end", DAY.plusDays(2).toString())
            .get("/internal/notes")
            .then().statusCode(200)
            .body("size()", org.hamcrest.Matchers.is(2))
            .body("'" + DAY.minusDays(1) + "'", org.hamcrest.Matchers.equalTo("Yesterday"))
            .body("'" + DAY + "'", org.hamcrest.Matchers.equalTo("Today"));
    }

    @Test
    void feed_withNoNotesInTheRange_isEmpty() {
        // The sparse shape matters: the browser treats "absent" as "this day has no note", so an empty object is the whole answer.
        given().queryParam("start", DAY.toString())
            .queryParam("end", DAY.plusDays(5).toString())
            .get("/internal/notes")
            .then().statusCode(200)
            .body("size()", org.hamcrest.Matchers.is(0));
    }

    @Test
    void feed_withoutARange_isBadRequest() {
        given().get("/internal/notes")
            .then().statusCode(400);
    }

    @Test
    void feed_answersNotModifiedForAnUnchangedRange() {
        runInTx(() -> newNote(userId, DAY, "Today"));

        final String tag = given().queryParam("start", DAY.toString())
            .queryParam("end", DAY.toString())
            .get("/internal/notes")
            .then().statusCode(200)
            .extract().header("ETag");

        given().header("If-None-Match", tag)
            .queryParam("start", DAY.toString())
            .queryParam("end", DAY.toString())
            .get("/internal/notes")
            .then().statusCode(304);
    }

    // ── save ──────────────────────────────────────────────────────────────────

    @Test
    void save_writesTheNote_andEchoesTheStoredForm() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"  Ran 5k   before work  \"}")
            .post(DAY_PATH)
            .then().statusCode(200)
            .body("content", org.hamcrest.Matchers.equalTo("Ran 5k before work"));

        runInTx(() -> assertThat(storedNoteContent(userId, DAY))
            .as("the normalised value must be what is persisted")
            .isEqualTo("Ran 5k before work"));
    }

    @Test
    void save_keepsTheLineBreaksTheUserWrote() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Line one\\nLine two\"}")
            .post(DAY_PATH)
            .then().statusCode(200)
            .body("content", org.hamcrest.Matchers.equalTo("Line one\nLine two"));
    }

    @Test
    void save_forAFutureDate_isAccepted() {
        final LocalDate future = DAY.plusDays(10);

        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Written ahead of time\"}")
            .post("/internal/notes/" + future)
            .then().statusCode(200);

        runInTx(() -> assertThat(Note.findEntry(userId, future))
            .as("the note box stays live on a day the action logger refuses")
            .isNotNull());
    }

    @Test
    void save_withBlankContent_removesTheNote() {
        runInTx(() -> newNote(userId, DAY, "To be cleared"));

        given().contentType(ContentType.JSON)
            .body("{\"content\":\"   \"}")
            .post(DAY_PATH)
            .then().statusCode(200)
            .body("content", org.hamcrest.Matchers.equalTo(""));

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("clearing the box and saving is how a note is deleted")
            .isNull());
    }

    @Test
    void save_overTheLengthBound_isUnprocessable() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"" + "x".repeat(TextFields.NOTE_MAX_LENGTH + 1) + "\"}")
            .post(DAY_PATH)
            .then().statusCode(422);

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("a rejected save must persist nothing")
            .isNull());
    }

    @Test
    void save_withAnInvisibleCharacter_isUnprocessable() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Ran 5k\\u200bbefore work\"}")
            .post(DAY_PATH)
            .then().statusCode(422);
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    void clear_removesTheNote() {
        runInTx(() -> newNote(userId, DAY, "To be removed"));

        given().post(DAY_PATH + "/delete")
            .then().statusCode(200)
            .body("content", org.hamcrest.Matchers.equalTo(""));

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("the note must be gone")
            .isNull());
    }

    @Test
    void clear_onADayWithNoNote_isStillSuccess() {
        given().post(DAY_PATH + "/delete")
            .then().statusCode(200);
    }
}
