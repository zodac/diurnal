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
import static net.zodac.diurnal.http.HttpStatusCodes.NOT_MODIFIED;
import static net.zodac.diurnal.http.HttpStatusCodes.OK;
import static net.zodac.diurnal.http.HttpStatusCodes.UNPROCESSABLE_ENTITY;
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
@TestSecurity(user = NotesInternalResourceIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
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
            .then().statusCode(OK)
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
            .then().statusCode(OK)
            .body("size()", org.hamcrest.Matchers.is(0));
    }

    @Test
    void feed_withoutARange_isBadRequest() {
        given().get("/internal/notes")
            .then().statusCode(BAD_REQUEST);
    }

    @Test
    void feed_answersNotModifiedForAnUnchangedRange() {
        runInTx(() -> newNote(userId, DAY, "Today"));

        final String tag = given().queryParam("start", DAY.toString())
            .queryParam("end", DAY.toString())
            .get("/internal/notes")
            .then().statusCode(OK)
            .extract().header("ETag");

        given().header("If-None-Match", tag)
            .queryParam("start", DAY.toString())
            .queryParam("end", DAY.toString())
            .get("/internal/notes")
            .then().statusCode(NOT_MODIFIED);
    }

    // ── save ──────────────────────────────────────────────────────────────────

    @Test
    void save_writesTheNote_andEchoesTheStoredForm() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"  Ran 5k   before work  \"}")
            .post(DAY_PATH)
            .then().statusCode(OK)
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
            .then().statusCode(OK)
            .body("content", org.hamcrest.Matchers.equalTo("Line one\nLine two"));
    }

    @Test
    void save_forAFutureDate_isAccepted() {
        final LocalDate future = DAY.plusDays(10);

        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Written ahead of time\"}")
            .post("/internal/notes/" + future)
            .then().statusCode(OK);

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
            .then().statusCode(OK)
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
            .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("a rejected save must persist nothing")
            .isNull());
    }

    @Test
    void save_withAnInvisibleCharacter_isUnprocessable() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Ran 5k\\u200bbefore work\"}")
            .post(DAY_PATH)
            .then().statusCode(UNPROCESSABLE_ENTITY);
    }

    // ── clear ─────────────────────────────────────────────────────────────────

    @Test
    void clear_removesTheNote() {
        runInTx(() -> newNote(userId, DAY, "To be removed"));

        given().post(DAY_PATH + "/delete")
            .then().statusCode(OK)
            .body("content", org.hamcrest.Matchers.equalTo(""));

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("the note must be gone")
            .isNull());
    }

    @Test
    void clear_onADayWithNoNote_isStillSuccess() {
        given().post(DAY_PATH + "/delete")
            .then().statusCode(OK);
    }

    // ── the notes-page list ───────────────────────────────────────────────────

    @Test
    void list_returnsEveryNote_latestFirst() {
        runInTx(() -> {
            newNote(userId, DAY.minusDays(2), "Two days back");
            newNote(userId, DAY, "Today");
        });

        final String html = given().get("/internal/notes/list")
            .then().statusCode(OK)
            .extract().asString();

        assertThat(html.indexOf("Today")).as("both notes must be listed").isNotNegative();
        assertThat(html.indexOf(DAY.toString()))
            .as("the newest note must be rendered before the older one")
            .isLessThan(html.indexOf(DAY.minusDays(2).toString()));
    }

    @Test
    void list_filtersToTheSearchTermAndMarksTheMatch() {
        runInTx(() -> {
            newNote(userId, DAY.minusDays(1), "Swam at the pool");
            newNote(userId, DAY, "Ran a 5k before work");
        });

        final String html = given().queryParam("q", "5k")
            .get("/internal/notes/list")
            .then().statusCode(OK)
            .extract().asString();

        assertThat(html)
            .as("the matching note's day must be listed, with the matched run wrapped for highlighting")
            .contains(DAY.toString())
            .contains("<mark class=\"note-mark\">5k</mark>");
        assertThat(html)
            .as("the non-matching note must be filtered out")
            .doesNotContain(DAY.minusDays(1).toString());
    }

    @Test
    void list_withNoMatches_rendersTheSearchEmptyState() {
        runInTx(() -> newNote(userId, DAY, "Ran a 5k"));

        assertThat(given().queryParam("q", "cycling").get("/internal/notes/list").then().statusCode(OK).extract().asString())
            .as("a search matching nothing must say so, rather than showing the 'no notes yet' copy")
            .contains("No notes match your search.");
    }

    @Test
    void list_withNoNotesAtAll_rendersTheEmptyState() {
        assertThat(given().get("/internal/notes/list").then().statusCode(OK).extract().asString())
            .as("an account with no notes must be pointed at where a note is written")
            .contains("No notes yet");
    }

    @Test
    void list_escapesNoteContentRatherThanRenderingIt() {
        // A note is stored verbatim, including markup, and is made safe where it is RENDERED. The snippet is built as a list of parts precisely so
        // this escaping still applies to every character of it.
        runInTx(() -> newNote(userId, DAY, "<script>alert(1)</script>"));

        assertThat(given().get("/internal/notes/list").then().statusCode(OK).extract().asString())
            .as("note content must be escaped in the list, never rendered as markup")
            .doesNotContain("<script>alert(1)</script>")
            .contains("&lt;script&gt;");
    }

    @Test
    void list_neverShowsAnotherUsersNotes() {
        final UUID[] otherId = new UUID[1];
        runInTx(() -> otherId[0] = newUser("notes-internal-other@lt.test", "Other").id);
        runInTx(() -> newNote(otherId[0], DAY, "Someone else's private entry"));

        assertThat(given().get("/internal/notes/list").then().statusCode(OK).extract().asString())
            .as("the list is scoped to the acting user")
            .doesNotContain("Someone else's private entry")
            .contains("No notes yet");
    }
}
