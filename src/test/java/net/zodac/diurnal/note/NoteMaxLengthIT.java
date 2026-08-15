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
import static net.zodac.diurnal.http.HttpStatusCodes.UNPROCESSABLE_ENTITY;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.time.LocalDate;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.user.Role;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

/**
 * The configured {@code NOTE_MAX_LENGTH} in force, end to end: that a deployment's own bound - not the catalogue default - is what every surface
 * applies, and that LOWERING it leaves notes already written above it entirely intact.
 *
 * <p>
 * The retention half is the part worth pinning. A note's bound is enforced only when one is written, and there is no column width behind it (the
 * plaintext column was dropped in {@code V28}), so an over-long note simply keeps working: it reads back in full, and the note box - which carries no
 * {@code maxlength} - shows all of it rather than silently cutting it. What such a note cannot do is be saved again unedited, which is the deliberate
 * cost of the retention.
 */
@QuarkusTest
@TestProfile(NoteMaxLengthProfile.class)
@TestSecurity(user = NoteMaxLengthIT.PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NoteMaxLengthIT extends IntegrationTestBase {

    static final String PRIMARY = "note-max-length-it@lt.test";

    private static final LocalDate DAY = FIXED_TODAY;
    private static final String INTERNAL_PATH = "/internal/notes/" + FIXED_TODAY;
    private static final String API_PATH = "/api/v1/notes/" + FIXED_TODAY;

    private static final String AT_BOUND = "x".repeat(NoteMaxLengthProfile.MAX_LENGTH);
    private static final String OVER_BOUND = "x".repeat(NoteMaxLengthProfile.MAX_LENGTH + 1);

    private UUID userId;

    @Override
    protected void createDbState() {
        userId = newUser(PRIMARY, "Note Bound User").id;
    }

    // ── the configured bound is the one in force ──────────────────────────────

    @Test
    void configuredBound_isWellBelowTheDefault() {
        assertThat(NoteMaxLengthProfile.MAX_LENGTH)
            .as("every case here is meaningless unless the profile's bound is one the DEFAULT would have accepted")
            .isLessThan(TextFields.NOTE_MAX_LENGTH);
    }

    @Test
    void internalSave_atTheConfiguredBound_isAccepted() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"" + AT_BOUND + "\"}")
            .post(INTERNAL_PATH)
            .then().statusCode(OK);

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("a note exactly at the configured bound must be stored")
            .isNotNull());
    }

    @Test
    void internalSave_pastTheConfiguredBound_isRejected() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"" + OVER_BOUND + "\"}")
            .post(INTERNAL_PATH)
            .then().statusCode(UNPROCESSABLE_ENTITY)
            .body(Matchers.containsString("at most " + NoteMaxLengthProfile.MAX_LENGTH));

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("a rejected write must persist nothing")
            .isNull());
    }

    @Test
    void apiWrite_appliesTheSameConfiguredBound() {
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"" + AT_BOUND + "\"}")
            .put(API_PATH)
            .then().statusCode(OK);

        given().contentType(ContentType.JSON)
            .body("{\"content\":\"" + OVER_BOUND + "\"}")
            .put(API_PATH)
            .then().statusCode(BAD_REQUEST);
    }

    @Test
    void dashboard_publishesTheConfiguredBoundToTheNoteBox() {
        given().get("/")
            .then().statusCode(OK)
            .body(Matchers.containsString("data-note-max=\"" + NoteMaxLengthProfile.MAX_LENGTH + '"'));
    }

    // ── lowering the bound keeps the notes already written ────────────────────

    @Test
    void noteWrittenBeforeTheBoundWasLowered_isStillReadInFull() {
        final String legacy = "x".repeat(NoteMaxLengthProfile.MAX_LENGTH * 3);
        runInTx(() -> newNote(userId, DAY, legacy));

        given().queryParam("start", DAY.toString())
            .queryParam("end", DAY.toString())
            .get("/internal/notes")
            .then().statusCode(OK)
            .body("'" + DAY + "'", Matchers.equalTo(legacy));
    }

    @Test
    void noteWrittenBeforeTheBoundWasLowered_survivesInStorage() {
        final String legacy = "x".repeat(NoteMaxLengthProfile.MAX_LENGTH * 3);
        runInTx(() -> newNote(userId, DAY, legacy));

        // Nothing re-validates a stored note and no column width can be breached, so the row is untouched by the change.
        given().contentType(ContentType.JSON)
            .body("{\"content\":\"" + legacy + "\"}")
            .post(INTERNAL_PATH)
            .then().statusCode(UNPROCESSABLE_ENTITY);

        runInTx(() -> assertThat(Note.findEntry(userId, DAY))
            .as("refusing to re-save an over-long note must not delete the note it refused")
            .isNotNull());
    }
}
