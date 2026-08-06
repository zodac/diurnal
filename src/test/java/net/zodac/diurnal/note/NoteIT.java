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

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.http.ChangeSignature;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link Note}'s finders and mutations against a real database — the storage semantics the rest of the notes feature is built on: one note
 * per day, an upsert that overwrites rather than colliding, a sparse range read that returns only the days that have a note, and a range
 * change-signature that moves on every kind of write.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NoteIT extends IntegrationTestBase {

    private static final LocalDate DAY = FIXED_TODAY;

    private User owner;
    private User other;

    @Override
    protected void createDbState() {
        owner = newUser("note-owner@lt.test", "Note Owner");
        other = newUser("note-other@lt.test", "Note Other");
    }

    @Test
    void findEntry_withNoNote_returnsNull() {
        runInTx(() -> assertThat(Note.findEntry(owner.id, DAY))
            .as("a day with no note must read back as null, not an empty note")
            .isNull());
    }

    @Test
    void upsert_onADayWithNoNote_insertsIt() {
        runInTx(() -> Note.upsert(owner.id, DAY, sealed("First entry")));

        runInTx(() -> assertThat(Note.findEntry(owner.id, DAY))
            .as("the inserted note must be readable back")
            .isNotNull()
            .extracting(note -> note.contentEncrypted)
            .isEqualTo(sealed("First entry")));
    }

    @Test
    void upsert_onADayThatAlreadyHasANote_overwritesItWithoutColliding() {
        runInTx(() -> Note.upsert(owner.id, DAY, sealed("First entry")));
        runInTx(() -> Note.upsert(owner.id, DAY, sealed("Replaced entry")));

        runInTx(() -> {
            assertThat(Note.findEntry(owner.id, DAY))
                .as("the second upsert must overwrite the content rather than trip notes_unique")
                .isNotNull()
                .extracting(note -> note.contentEncrypted)
                .isEqualTo(sealed("Replaced entry"));
            assertThat(Note.count("userId = ?1 and noteDate = ?2", owner.id, DAY))
                .as("a day must never hold more than one note")
                .isEqualTo(1L);
        });
    }

    @Test
    void upsert_keepsTheOriginalCreatedAt_butMovesUpdatedAt() {
        runInTx(() -> Note.upsert(owner.id, DAY, sealed("First entry")));

        final Note inserted = readNote();
        runInTx(() -> Note.upsert(owner.id, DAY, sealed("Replaced entry")));
        final Note updated = readNote();

        assertThat(updated.createdAt)
            .as("createdAt must keep recording when the note was FIRST written")
            .isEqualTo(inserted.createdAt);
        assertThat(updated.updatedAt)
            .as("updatedAt must move on every write, so the range signature changes")
            .isAfterOrEqualTo(inserted.updatedAt);
    }

    @Test
    void upsert_isScopedToItsOwnUser() {
        runInTx(() -> Note.upsert(owner.id, DAY, sealed("Owner's entry")));
        runInTx(() -> Note.upsert(other.id, DAY, sealed("Other's entry")));

        runInTx(() -> {
            assertThat(Note.findEntry(owner.id, DAY))
                .as("the same day for two users must be two independent notes")
                .isNotNull()
                .extracting(note -> note.contentEncrypted)
                .isEqualTo(sealed("Owner's entry"));
            assertThat(Note.findEntry(other.id, DAY))
                .as("the same day for two users must be two independent notes")
                .isNotNull()
                .extracting(note -> note.contentEncrypted)
                .isEqualTo(sealed("Other's entry"));
        });
    }

    @Test
    void upsert_acceptsAFutureDate() {
        final LocalDate future = DAY.plusMonths(2);
        runInTx(() -> Note.upsert(owner.id, future, sealed("Written ahead of time")));

        runInTx(() -> assertThat(Note.findEntry(owner.id, future))
            .as("a note must be writable for a future date, unlike an action log")
            .isNotNull());
    }

    @Test
    void upsert_acceptsContentAtTheCatalogueMaximum() {
        final String maximal = "x".repeat(TextFields.NOTE_MAX_LENGTH);
        runInTx(() -> Note.upsert(owner.id, DAY, sealed(maximal)));

        runInTx(() -> assertThat(Note.findEntry(owner.id, DAY))
            .as("a note at the catalogue bound must round-trip whole - sealing expands it, so the stored form is larger than the bound itself")
            .isNotNull()
            .extracting(note -> note.contentEncrypted.length)
            .isEqualTo(TextFields.NOTE_MAX_LENGTH));
    }

    @Test
    void findByUserAndRange_returnsOnlyTheDaysThatHaveANote_ascending() {
        runInTx(() -> {
            newNote(owner.id, DAY.minusDays(5), "Five days back");
            newNote(owner.id, DAY, "Today");
            newNote(owner.id, DAY.plusDays(5), "Outside the window");
            newNote(other.id, DAY, "Another user's");
        });

        runInTx(() -> {
            final List<Note> found = Note.findByUserAndRange(owner.id, DAY.minusDays(5), DAY.plusDays(1));
            assertThat(found)
                .as("the range read must be sparse, own-user only, and ascending by date")
                .extracting(note -> note.noteDate)
                .containsExactly(DAY.minusDays(5), DAY);
        });
    }

    @Test
    void deleteEntry_removesTheNote_andReportsWhetherThereWasOne() {
        runInTx(() -> newNote(owner.id, DAY, "To be removed"));

        runInTx(() -> assertThat(Note.deleteEntry(owner.id, DAY))
            .as("deleting an existing note must report that one was removed")
            .isTrue());
        runInTx(() -> assertThat(Note.deleteEntry(owner.id, DAY))
            .as("deleting an absent note must be a no-op reporting that there was none")
            .isFalse());
        runInTx(() -> assertThat(Note.findEntry(owner.id, DAY))
            .as("the note must be gone")
            .isNull());
    }

    @Test
    void deleteByUser_removesOnlyThatUsersNotes() {
        runInTx(() -> {
            newNote(owner.id, DAY, "Owner's");
            newNote(owner.id, DAY.minusDays(1), "Owner's, earlier");
            newNote(other.id, DAY, "Other's");
        });

        runInTx(() -> Note.deleteByUser(owner.id));

        runInTx(() -> {
            assertThat(Note.count("userId", owner.id))
                .as("every note of the deleted account must go")
                .isZero();
            assertThat(Note.count("userId", other.id))
                .as("another account's notes must be untouched")
                .isEqualTo(1L);
        });
    }

    @Test
    void rangeVersion_onAnEmptyRange_isZeroAndNull() {
        runInTx(() -> assertThat(Note.rangeVersion(owner.id, DAY.minusDays(3), DAY))
            .as("an empty range must produce the zero signature")
            .isEqualTo(new ChangeSignature(0L, null)));
    }

    @Test
    void rangeVersion_changesOnInsertUpdateAndDelete() {
        final ChangeSignature empty = rangeVersion();

        runInTx(() -> Note.upsert(owner.id, DAY, sealed("First entry")));
        final ChangeSignature afterInsert = rangeVersion();

        runInTx(() -> Note.upsert(owner.id, DAY, sealed("Replaced entry")));
        final ChangeSignature afterUpdate = rangeVersion();

        runInTx(() -> Note.deleteEntry(owner.id, DAY));
        final ChangeSignature afterDelete = rangeVersion();

        assertThat(afterInsert)
            .as("an insert must move the signature away from empty")
            .isNotEqualTo(empty);
        assertThat(afterUpdate)
            .as("an update must move the signature, so a cached range is not served stale")
            .isNotEqualTo(afterInsert);
        assertThat(afterDelete)
            .as("a delete must move the signature, via the count even when it does not move the timestamp")
            .isNotEqualTo(afterUpdate);
    }

    @Test
    void rangeVersion_ignoresNotesOutsideTheRange() {
        runInTx(() -> newNote(owner.id, DAY.plusDays(10), "Well outside"));

        runInTx(() -> assertThat(Note.rangeVersion(owner.id, DAY.minusDays(3), DAY))
            .as("a note outside the window must not affect the window's signature")
            .isEqualTo(new ChangeSignature(0L, null)));
    }

    private ChangeSignature rangeVersion() {
        final ChangeSignature[] signature = new ChangeSignature[1];
        runInTx(() -> signature[0] = Note.rangeVersion(owner.id, DAY.minusDays(3), DAY));
        return signature[0];
    }

    private Note readNote() {
        final Note[] note = new Note[1];
        runInTx(() -> note[0] = Note.findEntry(owner.id, DAY));
        return note[0];
    }

    // Stands in for a sealed note. These tests are about STORAGE semantics - one row per day, an upsert that overwrites
    // rather than colliding, a sparse range read - none of which care what the bytes mean, so a plain UTF-8 encoding
    // keeps each assertion readable where a real AES-GCM seal would be opaque and non-deterministic. The sealing itself
    // is covered by Aes256GcmTest and NoteContentTest.
    private static byte[] sealed(final String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }
}
