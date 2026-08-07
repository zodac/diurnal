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

package net.zodac.diurnal;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.action.Action;
import net.zodac.diurnal.log.ActionLog;
import net.zodac.diurnal.note.Note;
import net.zodac.diurnal.user.Role;
import org.junit.jupiter.api.Test;

/**
 * Cross-surface parity guard: drives the SAME inputs through both the web UI's HTMX endpoints ({@code /internal/*}) and the public REST API
 * ({@code /api/v1/*}) and asserts equivalent outcomes (both reject, or both persist the same state). The rules themselves live in the shared
 * {@code ActionService}/{@code LogService}, so this is a belt-and-braces net for anything that bypasses them — statuses differ per surface
 * (banner {@code 409}s vs JSON {@code 400}/{@code 409}s), so the authoritative parity assertion is the resulting database state.
 */
@QuarkusTest
@TestSecurity(user = "parity-it@lt.test", roles = Role.Values.USER)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class SurfaceParityIT extends IntegrationTestBase {

    static final String PRIMARY = "parity-it@lt.test";

    static final int SUGGESTION_DRAWS = 10;

    // action.ActionValidation.DEFAULT_COLOUR, repeated here because it is package-private to that package.
    static final String NEUTRAL_COLOUR = "#64748b";

    static final LocalDate TODAY    = FIXED_TODAY;
    static final LocalDate TOMORROW = FIXED_TODAY.plusDays(1);

    UUID primaryId;
    Action action;

    @Override
    protected void createDbState() {
        primaryId = newUser(PRIMARY, "Parity User").id;
        action    = newAction(primaryId, "Running");
    }

    @Test
    void overlongActionName_rejectedOnBothSurfaces_nothingPersisted() {
        final String longName = "x".repeat(101);

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + longName + "\"}")
                .post("/api/v1/actions")
                .then().statusCode(400);

        given().formParam("name", longName)
                .post("/internal/actions")
                .then().statusCode(409); // the HTMX surface reports every validation failure as a conflict banner

        runInTx(() -> assertThat(Action.count("userId = ?1 and name = ?2", primaryId, longName))
            .as("an over-long name must be rejected by BOTH surfaces without persisting")
            .isZero());
    }

    @Test
    void invisibleCharacterInActionName_rejectedOnBothSurfaces_nothingPersisted() {
        // Renders as "Running" but is a different value, so accepting it on either surface would put two indistinguishable actions in the table.
        final String invisibleName = "Run" + Character.toString(0x200B) + "ning";

        given().contentType(ContentType.JSON)
                .body("{\"name\":\"" + invisibleName + "\"}")
                .post("/api/v1/actions")
                .then().statusCode(400);

        // The charset is explicit because RestAssured encodes a form body as ISO-8859-1 by default, which would drop the very character under test.
        given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .formParam("name", invisibleName)
                .post("/internal/actions")
                .then().statusCode(409); // the HTMX surface reports every validation failure as a conflict banner

        runInTx(() -> assertThat(Action.count("userId = ?1 and name = ?2", primaryId, invisibleName))
            .as("a name holding an invisible character must be rejected by BOTH surfaces without persisting")
            .isZero());
    }

    @Test
    void emojiActionName_acceptedOnBothSurfaces() {
        given().contentType(ContentType.JSON)
                .body("{\"name\":\"Gym 💪\"}")
                .post("/api/v1/actions")
                .then().statusCode(201);

        given().contentType("application/x-www-form-urlencoded; charset=UTF-8")
                .formParam("name", "Yoga 🧘")
                .post("/internal/actions")
                .then().statusCode(200);

        runInTx(() -> assertThat(Action.count("userId = ?1 and (name = ?2 or name = ?3)", primaryId, "Gym 💪", "Yoga 🧘"))
            .as("an emoji name must round-trip through BOTH surfaces and the column unchanged")
            .isEqualTo(2));
    }

    @Test
    void duplicateActionName_rejectedOnBothSurfaces_countUnchanged() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Running"}
                        """)
                .post("/api/v1/actions")
                .then().statusCode(409);

        given().formParam("name", "Running")
                .post("/internal/actions")
                .then().statusCode(409);

        runInTx(() -> assertThat(Action.count("userId = ?1 and name = ?2", primaryId, "Running"))
            .as("a duplicate name must be rejected by BOTH surfaces, leaving the single original")
            .isEqualTo(1));
    }

    @Test
    void randomColour_bothSurfacesAvoidTheColoursInUse_andPersistNothing() {
        // The suggestion is random, so parity is asserted over what BOTH surfaces must never do: offer a colour the user already has, or
        // write anything at all. The seeded action's colour is itself taken from a suggestion, so it is guaranteed to be one of the
        // candidates the next call would otherwise draw.
        final String taken = given().get("/api/v1/actions/random-colour")
            .then().statusCode(200)
            .extract().path("colour");
        runInTx(() -> Action.<Action>findById(action.id).colour = taken);

        for (int i = 0; i < SUGGESTION_DRAWS; i++) {
            assertThat(given().get("/api/v1/actions/random-colour").then().statusCode(200).extract().path("colour").toString())
                .as("the API must not suggest a colour already in use")
                .isNotEqualTo(taken);
            assertThat(given().get("/internal/actions/random-colour").then().statusCode(200).extract().path("colour").toString())
                .as("the web surface must not suggest a colour already in use")
                .isNotEqualTo(taken);
        }

        runInTx(() -> assertThat(Action.count("userId = ?1", primaryId))
            .as("suggesting a colour must persist nothing on either surface")
            .isEqualTo(1));
    }

    @Test
    void actionCreatedWithoutAColour_takesASuggestionOnBothSurfaces() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"name":"Colourless API"}
                        """)
                .post("/api/v1/actions")
                .then().statusCode(201);

        given().formParam("name", "Colourless Web")
                .post("/internal/actions")
                .then().statusCode(200);

        runInTx(() -> {
            assertThat(Action.count("userId = ?1 and colour = ?2", primaryId, NEUTRAL_COLOUR))
                .as("an omitted colour must be filled in with a suggestion by BOTH surfaces, never left as the neutral slate")
                .isZero();
            assertThat(colourOf("Colourless API"))
                .as("the two surfaces must draw from the same suggester, so neither may repeat a colour already in use")
                .isNotEqualTo(colourOf("Colourless Web"));
        });
    }

    @Test
    void futureDateWrite_rejectedOnBothSurfaces_noEntryCreated() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"count":1}
                        """)
                .put("/api/v1/logs/" + TOMORROW + "/" + action.id)
                .then().statusCode(400);

        given().formParam("amount", "1")
                .post("/internal/logs/" + TOMORROW + "/" + action.id + "/increment")
                .then().statusCode(400);

        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TOMORROW))
            .as("a future-date write must be rejected by BOTH surfaces without creating an entry")
            .isNull());
    }

    @Test
    void dailyCap_webSaturates_apiRejects() {
        // The shared write ceiling (MAX_DAILY_COUNT) is identical on both surfaces; only the INPUT contract
        // at that ceiling deliberately differs — the web form saturates an over-cap increment to the cap,
        // whereas the API rejects it (never silently changing the caller's value).
        runInTx(() -> newLog(primaryId, action.id, TODAY, 998));

        given().formParam("amount", "10")
                .post("/internal/logs/" + TODAY + "/" + action.id + "/increment")
                .then().statusCode(200);
        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY).count)
            .as("the HTMX increment must saturate the count at 999")
            .isEqualTo(999));

        runInTx(() -> ActionLog.setCount(primaryId, action.id, TODAY, 998));
        given().contentType(ContentType.JSON)
                .body("""
                        {"amount":10}
                        """)
                .post("/api/v1/logs/" + TODAY + "/" + action.id + "/increment")
                .then().statusCode(400);
        runInTx(() -> assertThat(ActionLog.findEntry(primaryId, action.id, TODAY).count)
            .as("the API increment must reject the over-cap write, leaving the count unchanged")
            .isEqualTo(998));
    }

    @Test
    void outOfRangePageSize_rejectedOnBothSurfaces_valueUnchanged() {
        given().contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"pageSize":9999}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(400);

        given().formParam("pageSize", "9999")
                .patch("/internal/settings")
                .then().statusCode(422);

        runInTx(() -> assertThat(net.zodac.diurnal.user.User.findByEmail(PRIMARY).orElseThrow().pageSize)
            .as("an out-of-range page size must be rejected by BOTH surfaces, keeping the previous value")
            .isEqualTo(net.zodac.diurnal.user.UserSettings.DEFAULT_PAGE_SIZE));
    }

    @Test
    void noteColour_appliedIdenticallyOnBothSurfaces_andMalformedRejectedByBoth() {
        // One rule (ProfileService.updateNoteColour): both surfaces store the picked value verbatim, and both reject anything that is not a
        // #rrggbb hex rather than falling back to the default.
        given().formParam("noteColour", "#0284c7")
                .patch("/internal/settings")
                .then().statusCode(204);
        runInTx(() -> assertThat(net.zodac.diurnal.user.User.findByEmail(PRIMARY).orElseThrow().noteColour)
            .as("the web form stores the picked colour")
            .isEqualTo("#0284c7"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"noteColour":"#d946ef"}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(200);
        runInTx(() -> assertThat(net.zodac.diurnal.user.User.findByEmail(PRIMARY).orElseThrow().noteColour)
            .as("the API stores the picked colour the very same way")
            .isEqualTo("#d946ef"));

        given().contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"noteColour":"lime"}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(400);

        given().formParam("noteColour", "lime")
                .patch("/internal/settings")
                .then().statusCode(422);

        runInTx(() -> assertThat(net.zodac.diurnal.user.User.findByEmail(PRIMARY).orElseThrow().noteColour)
            .as("a malformed colour must be rejected by BOTH surfaces, keeping the previous value")
            .isEqualTo("#d946ef"));
    }

    @Test
    void statFieldRename_appliedIdenticallyOnBothSurfaces() {
        // Renaming a stat is one shared rule (ProfileService.updateStatsFields): both surfaces normalise
        // the name the same way, and both REJECT an over-long one rather than truncating it.
        given().formParam("statsOrder", "current-streak", "last-performed")
                .formParam("statsEnabled", "current-streak")
                .formParam("statsLabel", "  Days in row  ", "")
                .patch("/internal/settings")
                .then().statusCode(204);
        runInTx(() -> assertThat(net.zodac.diurnal.user.User.findByEmail(PRIMARY).orElseThrow().statsFields)
            .as("the web form stores the sanitised name")
            .contains(new net.zodac.diurnal.user.StatFieldPref("current-streak", true, "Days in row")));

        given().contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"statsFields":[
                            {"key":"current-streak","enabled":true,"label":"  Days in row  "},
                            {"key":"last-performed","enabled":true}
                        ]}}
                        """)
                .patch("/api/v1/users/me")
                .then().statusCode(200);
        runInTx(() -> assertThat(net.zodac.diurnal.user.User.findByEmail(PRIMARY).orElseThrow().statsFields)
            .as("the API stores the very same name for the very same input")
            .contains(new net.zodac.diurnal.user.StatFieldPref("current-streak", true, "Days in row")));

        final String tooLong = "a".repeat(net.zodac.diurnal.stats.StatField.MAX_LABEL_LENGTH + 1);
        given().contentType(ContentType.JSON)
                .body("""
                        {"preferences":{"statsFields":[{"key":"current-streak","enabled":true,"label":"%s"}]}}
                        """.formatted(tooLong))
                .patch("/api/v1/users/me")
                .then().statusCode(400);

        given().formParam("statsOrder", "current-streak", "last-performed")
                .formParam("statsEnabled", "current-streak")
                .formParam("statsLabel", tooLong, "")
                .patch("/internal/settings")
                .then().statusCode(422);

        runInTx(() -> assertThat(net.zodac.diurnal.user.User.findByEmail(PRIMARY).orElseThrow().statsFields)
            .as("an over-long name must be rejected by BOTH surfaces, keeping the previous rename")
            .contains(new net.zodac.diurnal.user.StatFieldPref("current-streak", true, "Days in row")));
    }

    // ── notes ─────────────────────────────────────────────────────────────────

    @Test
    void overlongNote_rejectedOnBothSurfaces_nothingPersisted() {
        final String tooLong = "x".repeat(net.zodac.diurnal.text.TextFields.NOTE_MAX_LENGTH + 1);

        given().contentType(ContentType.JSON)
                .body("{\"content\":\"" + tooLong + "\"}")
                .put("/api/v1/notes/" + TODAY)
                .then().statusCode(400);

        given().contentType(ContentType.JSON)
                .body("{\"content\":\"" + tooLong + "\"}")
                .post("/internal/notes/" + TODAY)
                .then().statusCode(422); // the web surface answers 422 where the API answers 400

        runInTx(() -> assertThat(Note.findEntry(primaryId, TODAY))
            .as("an over-long note must be rejected by BOTH surfaces without persisting")
            .isNull());
    }

    @Test
    void invisibleCharacterInNote_rejectedOnBothSurfaces_nothingPersisted() {
        given().contentType(ContentType.JSON)
                .body("{\"content\":\"Ran 5k\\u200bbefore work\"}")
                .put("/api/v1/notes/" + TODAY)
                .then().statusCode(400);

        given().contentType(ContentType.JSON)
                .body("{\"content\":\"Ran 5k\\u200bbefore work\"}")
                .post("/internal/notes/" + TODAY)
                .then().statusCode(422);

        runInTx(() -> assertThat(Note.findEntry(primaryId, TODAY))
            .as("the shared content policy must reject a note on BOTH surfaces without persisting")
            .isNull());
    }

    @Test
    void multiLineNote_storedIdenticallyByBothSurfaces() {
        // The normalisation pass is the interesting part: both surfaces must condense the same blank-line run and keep the same paragraph break.
        final String expected = "First\n\nSecond";

        given().contentType(ContentType.JSON)
                .body("{\"content\":\"First\\r\\n\\r\\n\\r\\n\\r\\nSecond\"}")
                .put("/api/v1/notes/" + TODAY)
                .then().statusCode(200);
        runInTx(() -> assertThat(storedNoteContent(primaryId, TODAY))
            .as("the API must store the normalised multi-line form")
            .isEqualTo(expected));

        given().contentType(ContentType.JSON)
                .body("{\"content\":\"First\\r\\n\\r\\n\\r\\n\\r\\nSecond\"}")
                .post("/internal/notes/" + TOMORROW)
                .then().statusCode(200);
        runInTx(() -> assertThat(storedNoteContent(primaryId, TOMORROW))
            .as("the web surface must store byte-identically to the API")
            .isEqualTo(expected));
    }

    @Test
    void futureDatedNote_acceptedOnBothSurfaces() {
        // The deliberate asymmetry with logging: an action cannot be logged against tomorrow (futureDateWrite_rejectedOnBothSurfaces_noEntryCreated
        // pins that), but a note CAN be written against it. Both surfaces must agree, or the API and the dashboard would disagree about a future day.
        final LocalDate farFuture = TODAY.plusMonths(2);

        given().contentType(ContentType.JSON)
                .body("""
                        {"content":"Booked the day off"}
                        """)
                .put("/api/v1/notes/" + farFuture)
                .then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("""
                        {"content":"Booked the day off"}
                        """)
                .post("/internal/notes/" + TOMORROW)
                .then().statusCode(200);

        runInTx(() -> assertThat(Note.count("userId = ?1 and (noteDate = ?2 or noteDate = ?3)", primaryId, farFuture, TOMORROW))
            .as("a future-dated note must be accepted by BOTH surfaces")
            .isEqualTo(2));
    }

    @Test
    void blankNote_clearsOnBothSurfaces() {
        runInTx(() -> {
            newNote(primaryId, TODAY, "To be cleared by the API");
            newNote(primaryId, TOMORROW, "To be cleared by the web form");
        });

        given().contentType(ContentType.JSON)
                .body("""
                        {"content":"   "}
                        """)
                .put("/api/v1/notes/" + TODAY)
                .then().statusCode(200);

        given().contentType(ContentType.JSON)
                .body("""
                        {"content":"   "}
                        """)
                .post("/internal/notes/" + TOMORROW)
                .then().statusCode(200);

        runInTx(() -> assertThat(Note.count("userId = ?1", primaryId))
            .as("a blank note must remove the row on BOTH surfaces, rather than storing an empty one")
            .isZero());
    }

    @Test
    void noteSearch_matchesTheSameNotesOnBothSurfaces() {
        // The two surfaces deliberately differ in what they select and how they order it (the API a date range, earliest first; the page the whole
        // history, latest first) - but never in what COUNTS as a match. Same term, same set of days, or the notes page and the API disagree about
        // whether a note mentions something.
        runInTx(() -> {
            newNote(primaryId, TODAY, "Ran a 5K before work");
            newNote(primaryId, TOMORROW, "Swam at the pool");
        });

        given().queryParam("q", "5k")
                .get("/api/v1/notes")
                .then().statusCode(200)
                .body("totalCount", org.hamcrest.Matchers.is(1))
                .body("items[0].date", org.hamcrest.Matchers.equalTo(TODAY.toString()));

        final String html = given().queryParam("q", "5k")
            .get("/internal/notes/list")
            .then().statusCode(200)
            .extract().asString();

        assertThat(html)
            .as("the same case-insensitive term must select the same day on the web surface")
            .contains(TODAY.toString())
            .doesNotContain(TOMORROW.toString());
    }

    @Test
    void unownedAction_rejectedOnBothSurfaces() {
        final UUID unknown = UUID.randomUUID();

        given().contentType(ContentType.JSON)
                .body("""
                        {"count":1}
                        """)
                .put("/api/v1/logs/" + TODAY + "/" + unknown)
                .then().statusCode(404);

        given().formParam("count", "1")
                .post("/internal/logs/" + TODAY + "/" + unknown + "/set")
                .then().statusCode(404);
    }

    private String colourOf(final String name) {
        return Action.<Action>find("userId = ?1 and name = ?2", primaryId, name)
                .firstResultOptional()
                .orElseThrow()
                .colour;
    }
}
