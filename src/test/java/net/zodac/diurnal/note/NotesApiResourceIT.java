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
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * The public notes API: reading a day and a range, writing, clearing, and the input contract at each boundary. The write rules themselves live in
 * {@link NoteService} and are shared with the web surface (see {@code SurfaceParityIT}); this pins the JSON translation and the status codes.
 */
@QuarkusTest
@TestSecurity(user = NotesApiResourceIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NotesApiResourceIT extends IntegrationTestBase {

    static final String PRIMARY = "notes-api-it@lt.test";

    private static final LocalDate DAY = FIXED_TODAY;
    private static final String DAY_PATH = "/api/v1/notes/" + FIXED_TODAY;

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Notes API User").id;
    }

    // ── read one day ──────────────────────────────────────────────────────────

    @Test
    void readNote_withNoNote_isNotFound() {
        given().get(DAY_PATH)
            .then().statusCode(NOT_FOUND);
    }

    @Test
    void readNote_returnsTheStoredContent() {
        runInTx(() -> newNote(userId, DAY, "Ran 5k"));

        given().get(DAY_PATH)
            .then().statusCode(OK)
            .body("date", org.hamcrest.Matchers.equalTo(DAY.toString()))
            .body("content", org.hamcrest.Matchers.equalTo("Ran 5k"));
    }

    @Test
    void readNote_withMalformedDate_isBadRequest() {
        given().get("/api/v1/notes/not-a-date")
            .then().statusCode(BAD_REQUEST);
    }

    // ── read a range ──────────────────────────────────────────────────────────

    @Test
    void listNotes_returnsOnlyTheDaysWithANote_earliestFirst() {
        runInTx(() -> {
            newNote(userId, DAY.minusDays(2), "Two days back");
            newNote(userId, DAY, "Today");
            newNote(userId, DAY.plusDays(9), "Outside the window");
        });

        given().queryParam("start", DAY.minusDays(3).toString())
            .queryParam("end", DAY.plusDays(1).toString())
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("items.size()", org.hamcrest.Matchers.is(2))
            .body("totalCount", org.hamcrest.Matchers.is(2))
            .body("totalPages", org.hamcrest.Matchers.is(1))
            .body("currentPage", org.hamcrest.Matchers.is(1))
            .body("items[0].date", org.hamcrest.Matchers.equalTo(DAY.minusDays(2).toString()))
            .body("items[1].date", org.hamcrest.Matchers.equalTo(DAY.toString()));
    }

    @Test
    void listNotes_pagesAtOneCalendarMonth() {
        // A note may run to 10,000 characters, so an unbounded range would be the largest response the API can produce. The page size is fixed at 31
        // - one full month - rather than following the user's page-size preference, which they could set to 5 and make the bound meaningless.
        runInTx(() -> {
            for (int i = 0; i < 40; i++) {
                newNote(userId, DAY.minusDays(i), "Note " + i);
            }
        });

        final String start = DAY.minusDays(39).toString();
        final String end = DAY.toString();

        given().queryParam("start", start).queryParam("end", end)
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("items.size()", org.hamcrest.Matchers.is(31))
            .body("totalCount", org.hamcrest.Matchers.is(40))
            .body("totalPages", org.hamcrest.Matchers.is(2))
            .body("currentPage", org.hamcrest.Matchers.is(1))
            // Earliest first, and page 1 starts at the range's own start.
            .body("items[0].date", org.hamcrest.Matchers.equalTo(start));

        given().queryParam("start", start).queryParam("end", end).queryParam("page", 2)
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("items.size()", org.hamcrest.Matchers.is(9))
            .body("currentPage", org.hamcrest.Matchers.is(2))
            .body("items[8].date", org.hamcrest.Matchers.equalTo(end));
    }

    @Test
    void listNotes_outOfRangePage_isRejectedNotClamped() {
        // The same input contract every other paginated endpoint has: a page number is never silently answered with some other page.
        runInTx(() -> newNote(userId, DAY, "Only note"));

        given().queryParam("start", DAY.toString()).queryParam("end", DAY.toString()).queryParam("page", 2)
            .get("/api/v1/notes")
            .then().statusCode(BAD_REQUEST);

        given().queryParam("start", DAY.toString()).queryParam("end", DAY.toString()).queryParam("page", 0)
            .get("/api/v1/notes")
            .then().statusCode(BAD_REQUEST);
    }

    @Test
    void listNotes_emptyRange_isAnEmptyFirstPage() {
        given().queryParam("start", DAY.toString()).queryParam("end", DAY.toString())
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("items.size()", org.hamcrest.Matchers.is(0))
            .body("totalCount", org.hamcrest.Matchers.is(0))
            .body("currentPage", org.hamcrest.Matchers.is(1));
    }

    @Test
    void listNotes_withoutRange_coversTheWholeHistory_earliestFirst() {
        // The range became optional when the notes page's search needed an unbounded twin. Omitting BOTH bounds is the whole journal, still ordered
        // earliest first - this endpoint's published ordering, even though the page it mirrors reads newest first.
        runInTx(() -> {
            newNote(userId, DAY.minusYears(2), "Long ago");
            newNote(userId, DAY, "Today");
        });

        given().get("/api/v1/notes")
            .then().statusCode(OK)
            .body("totalCount", org.hamcrest.Matchers.is(2))
            .body("items[0].date", org.hamcrest.Matchers.equalTo(DAY.minusYears(2).toString()))
            .body("items[1].date", org.hamcrest.Matchers.equalTo(DAY.toString()));
    }

    @Test
    void listNotes_withHalfRange_isBadRequest() {
        // Half a range is a request the caller did not mean to make, so it is rejected rather than being quietly completed with an open end.
        given().queryParam("start", DAY.toString())
            .get("/api/v1/notes")
            .then().statusCode(BAD_REQUEST);

        given().queryParam("end", DAY.toString())
            .get("/api/v1/notes")
            .then().statusCode(BAD_REQUEST);
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void listNotes_withSearchTerm_keepsOnlyMatchingNotes() {
        runInTx(() -> {
            newNote(userId, DAY.minusDays(2), "Ran a 5k before work");
            newNote(userId, DAY.minusDays(1), "Swam at the pool");
            newNote(userId, DAY, "Another 5K, faster this time");
        });

        given().queryParam("q", "5k")
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("totalCount", org.hamcrest.Matchers.is(2))
            .body("items[0].date", org.hamcrest.Matchers.equalTo(DAY.minusDays(2).toString()))
            .body("items[1].date", org.hamcrest.Matchers.equalTo(DAY.toString()));
    }

    @Test
    void listNotes_withSearchTerm_appliesInsideAGivenRange() {
        runInTx(() -> {
            newNote(userId, DAY.minusDays(5), "Ran a 5k");
            newNote(userId, DAY, "Ran a 5k");
        });

        given().queryParam("start", DAY.minusDays(1).toString())
            .queryParam("end", DAY.toString())
            .queryParam("q", "5k")
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("totalCount", org.hamcrest.Matchers.is(1))
            .body("items[0].date", org.hamcrest.Matchers.equalTo(DAY.toString()));
    }

    @Test
    void listNotes_withUnmatchedSearchTerm_isAnEmptyFirstPage() {
        runInTx(() -> newNote(userId, DAY, "Ran a 5k"));

        given().queryParam("q", "cycling")
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("totalCount", org.hamcrest.Matchers.is(0))
            .body("currentPage", org.hamcrest.Matchers.is(1));
    }

    @Test
    void listNotes_withUnmatchedSearchTerm_suggestsTheClosestWord() {
        runInTx(() -> newNote(userId, DAY, "The brass kaleidoscope in the junk shop"));

        given().queryParam("q", "kaleidoscpoe")
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("totalCount", org.hamcrest.Matchers.is(0))
            .body("suggestion", org.hamcrest.Matchers.equalTo("kaleidoscope"));
    }

    @Test
    void listNotes_withMatchingSearchTerm_suggestsNothing() {
        runInTx(() -> newNote(userId, DAY, "The brass kaleidoscope in the junk shop"));

        given().queryParam("q", "kaleidoscope")
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("totalCount", org.hamcrest.Matchers.is(1))
            .body("suggestion", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void listNotes_withNothingCloseToTheSearchTerm_suggestsNothing() {
        runInTx(() -> newNote(userId, DAY, "Cycled into town"));

        given().queryParam("q", "kaleidoscope")
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("suggestion", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void listNotes_neverSuggestsAWordFromAnotherUsersNotes() {
        final UUID[] otherId = new UUID[1];
        runInTx(() -> otherId[0] = newUser("notes-api-suggest-other@lt.test", "Other").id);
        runInTx(() -> newNote(otherId[0], DAY, "The brass kaleidoscope in the junk shop"));

        given().queryParam("q", "kaleidoscpoe")
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("suggestion", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void listNotes_withBlankSearchTerm_keepsEveryNote() {
        runInTx(() -> newNote(userId, DAY, "Ran a 5k"));

        given().queryParam("q", "   ")
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("totalCount", org.hamcrest.Matchers.is(1));
    }

    @Test
    void listNotes_searchNeverReachesAnotherUsersNotes() {
        final UUID[] otherId = new UUID[1];
        runInTx(() -> otherId[0] = newUser("notes-api-other@lt.test", "Other").id);
        runInTx(() -> newNote(otherId[0], DAY, "Ran a 5k"));

        given().queryParam("q", "5k")
            .get("/api/v1/notes")
            .then().statusCode(OK)
            .body("totalCount", org.hamcrest.Matchers.is(0));
    }

    // ── write ─────────────────────────────────────────────────────────────────

    @Test
    void putNote_createsTheNote() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Ran 5k before work\"}")
            .put(DAY_PATH)
            .then().statusCode(OK)
            .body("content", org.hamcrest.Matchers.equalTo("Ran 5k before work"));

        runInTx(() -> assertThat(storedNoteContent(userId, DAY))
            .as("the note must be persisted")
            .isEqualTo("Ran 5k before work"));
    }

    @Test
    void putNote_overwritesAnExistingNote() {
        runInTx(() -> newNote(userId, DAY, "First draft"));

        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Second draft\"}")
            .put(DAY_PATH)
            .then().statusCode(OK);

        runInTx(() -> assertThat(storedNoteContent(userId, DAY))
            .as("a second write must replace the content, not collide")
            .isEqualTo("Second draft"));
    }

    @Test
    void putNote_returnsTheNormalisedForm_notWhatWasSent() {
        // The stored value is always the pipeline's output, so the caller is told what it actually got.
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"  First\\r\\n\\r\\n\\r\\n\\r\\nSecond  \"}")
            .put(DAY_PATH)
            .then().statusCode(OK)
            .body("content", org.hamcrest.Matchers.equalTo("First\n\nSecond"));
    }

    @Test
    void putNote_keepsTheLineBreaksTheUserWrote() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Line one\\nLine two\"}")
            .put(DAY_PATH)
            .then().statusCode(OK)
            .body("content", org.hamcrest.Matchers.equalTo("Line one\nLine two"));
    }

    @Test
    void putNote_forAFutureDate_isAccepted() {
        // The one deliberate difference from a logged action, which is refused for a day that has not arrived.
        final LocalDate future = DAY.plusMonths(3);

        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Booked the day off\"}")
            .put("/api/v1/notes/" + future)
            .then().statusCode(OK);

        runInTx(() -> assertThat(Note.findEntry(userId, future))
            .as("a note must be writable against a future date")
            .isNotNull());
    }

    @Test
    void putNote_withBlankContent_removesTheNote() {
        runInTx(() -> newNote(userId, DAY, "To be cleared"));

        given().contentType(ContentType.JSON)
            .body("{\"content\":\"   \"}")
            .put(DAY_PATH)
            .then().statusCode(OK)
            .body("content", org.hamcrest.Matchers.equalTo(""));

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("a blank note is no note, mirroring a count of zero removing a log entry")
            .isNull());
    }

    @Test
    void putNote_withNoBody_removesTheNote() {
        runInTx(() -> newNote(userId, DAY, "To be cleared"));

        given().contentType(ContentType.JSON)
            .put(DAY_PATH)
            .then().statusCode(OK);

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("an omitted body is the same request as blank content")
            .isNull());
    }

    @Test
    void putNote_overTheLengthBound_isRejected() {
        final String tooLong = "x".repeat(TextFields.NOTE_MAX_LENGTH + 1);

        given().contentType(ContentType.JSON)
            .body("{\"content\":\"" + tooLong + "\"}")
            .put(DAY_PATH)
            .then().statusCode(BAD_REQUEST);

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("a rejected write must persist nothing")
            .isNull());
    }

    @Test
    void putNote_withAnInvisibleCharacter_isRejected() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Ran 5k\\u200bbefore work\"}")
            .put(DAY_PATH)
            .then().statusCode(BAD_REQUEST);

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("the shared content policy applies to a note exactly as it does to a name")
            .isNull());
    }

    @Test
    void putNote_withMalformedDate_isBadRequest() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"Anything\"}")
            .put("/api/v1/notes/2026-13-45")
            .then().statusCode(BAD_REQUEST);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    void deleteNote_removesIt() {
        runInTx(() -> newNote(userId, DAY, "To be removed"));

        given().delete(DAY_PATH)
            .then().statusCode(NO_CONTENT);

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("the note must be gone")
            .isNull());
    }

    @Test
    void deleteNote_onADayWithNoNote_isStillNoContent() {
        given().delete(DAY_PATH)
            .then().statusCode(NO_CONTENT);
    }
}
