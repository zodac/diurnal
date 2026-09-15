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

import static net.zodac.diurnal.DummyValues.DUMMY_UUID;
import static net.zodac.diurnal.DummyValues.OTHER_DUMMY_UUID;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.note.crypto.Aes256Gcm;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AttachmentContent}: the round trip of both halves of an attachment, and the binding that stops either from being opened
 * anywhere other than the owner, day and row it was written against — including being swapped for the other half.
 */
class AttachmentContentTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 6);
    private static final UUID ATTACHMENT_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_ATTACHMENT_ID = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");
    private static final String NAME = "Berlin route.png";
    private static final byte[] FILE = "not really a png".getBytes(StandardCharsets.UTF_8);

    @Test
    void sealThenOpenName_returnsTheOriginalName() {
        final byte[] dataKey = Aes256Gcm.randomKey();

        final byte[] sealed = AttachmentContent.sealName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, NAME);

        assertThat(AttachmentContent.openName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, sealed))
            .as("a name sealed and opened against the same owner, day and row should round-trip unchanged")
            .contains(NAME);
    }

    @Test
    void sealThenOpenName_survivesNonAsciiNames() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final String name = "休暇の写真 ☕.png";

        final byte[] sealed = AttachmentContent.sealName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, name);

        assertThat(AttachmentContent.openName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, sealed))
            .as("a name is encoded as UTF-8, so any script round-trips like anything else")
            .contains(name);
    }

    @Test
    void sealThenOpenFile_returnsTheOriginalBytes() {
        final byte[] dataKey = Aes256Gcm.randomKey();

        final byte[] sealed = AttachmentContent.sealFile(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, FILE);

        assertThat(AttachmentContent.openFile(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, sealed))
            .as("a file is opaque bytes and must come back byte-identical")
            .contains(FILE);
    }

    @Test
    void openFile_refusesOneFromAnotherAttachment() {
        final byte[] dataKey = Aes256Gcm.randomKey();

        final byte[] sealed = AttachmentContent.sealFile(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, FILE);

        assertThat(AttachmentContent.openFile(dataKey, DUMMY_UUID, DAY, OTHER_ATTACHMENT_ID, sealed))
            .as("a file lifted into another attachment's row does not open there")
            .isEmpty();
    }

    @Test
    void openName_refusesAnotherOwnersRow() {
        final byte[] dataKey = Aes256Gcm.randomKey();

        final byte[] sealed = AttachmentContent.sealName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, NAME);

        assertThat(AttachmentContent.openName(dataKey, OTHER_DUMMY_UUID, DAY, ATTACHMENT_ID, sealed))
            .as("the owner is bound into the seal, so moving a row to another account makes it fail to open rather than decrypt")
            .isEmpty();
    }

    @Test
    void openName_refusesAnotherDay() {
        final byte[] dataKey = Aes256Gcm.randomKey();

        final byte[] sealed = AttachmentContent.sealName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, NAME);

        assertThat(AttachmentContent.openName(dataKey, DUMMY_UUID, DAY.plusDays(1), ATTACHMENT_ID, sealed))
            .as("the day stays in the clear and an administrator can edit it, so binding it is what makes doing so detectable")
            .isEmpty();
    }

    @Test
    void openName_refusesAnotherRowsId() {
        final byte[] dataKey = Aes256Gcm.randomKey();

        final byte[] sealed = AttachmentContent.sealName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, NAME);

        assertThat(AttachmentContent.openName(dataKey, DUMMY_UUID, DAY, OTHER_ATTACHMENT_ID, sealed))
            .as("two files on the same day differ only by id, so the id has to be bound or either could be read as the other")
            .isEmpty();
    }

    @Test
    void openFile_refusesSealedNameInItsPlace() {
        final byte[] dataKey = Aes256Gcm.randomKey();

        final byte[] sealedName = AttachmentContent.sealName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, NAME);

        assertThat(AttachmentContent.openFile(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, sealedName))
            .as("every part is bound to which part it is, so a name pasted into the file column cannot be served as file bytes")
            .isEmpty();
    }

    @Test
    void openName_refusesAnotherKey() {
        final byte[] sealed = AttachmentContent.sealName(Aes256Gcm.randomKey(), DUMMY_UUID, DAY, ATTACHMENT_ID, NAME);

        assertThat(AttachmentContent.openName(Aes256Gcm.randomKey(), DUMMY_UUID, DAY, ATTACHMENT_ID, sealed))
            .as("a different account's data key opens nothing, which is the whole point of a per-user key")
            .isEmpty();
    }

    @Test
    void openNames_opensWholeDayUnderOneKeyAndKeepsTheOrder() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final SealedAttachment first = sealed(dataKey, ATTACHMENT_ID, "first.png");
        final SealedAttachment second = sealed(dataKey, OTHER_ATTACHMENT_ID, "second.png");

        assertThat(AttachmentContent.openNames(dataKey, DUMMY_UUID, List.of(first, second)))
            .as("a day's names are read in the order they were handed in, which is the order the card lists them")
            .containsExactly(Map.entry(ATTACHMENT_ID, "first.png"), Map.entry(OTHER_ATTACHMENT_ID, "second.png"));
    }

    @Test
    void openNames_omitsRowItCannotOpen() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final SealedAttachment readable = sealed(dataKey, ATTACHMENT_ID, "first.png");
        final SealedAttachment damaged = new SealedAttachment(OTHER_ATTACHMENT_ID, DAY, new byte[] {1, 2, 3}, new byte[] {4, 5, 6}, 3);

        assertThat(AttachmentContent.openNames(dataKey, DUMMY_UUID, List.of(readable, damaged)))
            .as("one damaged attachment must not take down the day it belongs to")
            .containsOnlyKeys(ATTACHMENT_ID);
    }

    @Test
    void fileName_roundTripsUnderItsOwnPurpose() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final byte[] sealedFileName = AttachmentContent.sealFileName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, "ticket-stub.png");

        assertThat(AttachmentContent.openFileName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, sealedFileName))
            .as("the name a file arrived under must come back exactly as it went in")
            .contains("ticket-stub.png");
    }

    @Test
    void fileName_doesNotOpenAsTheDisplayName() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final byte[] sealedFileName = AttachmentContent.sealFileName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, "ticket-stub.png");

        assertThat(AttachmentContent.openName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, sealedFileName))
            .as("the two names are bound under different purposes, so one pasted over the other fails to open rather than quietly undoing a rename")
            .isEmpty();
    }

    @Test
    void displayName_doesNotOpenAsTheFileName() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final byte[] sealedName = AttachmentContent.sealName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, "Berlin ticket");

        assertThat(AttachmentContent.openFileName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, sealedName))
            .as("and the substitution is refused in the other direction too")
            .isEmpty();
    }

    @Test
    void openFileNames_readsEachRowsUploadNameNotItsDisplayName() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final SealedAttachment renamed = sealedUploadedAs(dataKey, ATTACHMENT_ID, "Berlin ticket", "ticket-stub.png");

        assertThat(AttachmentContent.openFileNames(dataKey, DUMMY_UUID, List.of(renamed)))
            .as("the batch read must answer with what each file was called when it arrived")
            .containsExactly(Map.entry(ATTACHMENT_ID, "ticket-stub.png"));
    }

    @Test
    void openFileNames_omitsRowWhoseUploadNameWillNotOpen() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final SealedAttachment damaged = new SealedAttachment(ATTACHMENT_ID, DAY,
            AttachmentContent.sealName(dataKey, DUMMY_UUID, DAY, ATTACHMENT_ID, "first.png"), new byte[] {1, 2, 3}, FILE.length);

        assertThat(AttachmentContent.openFileNames(dataKey, DUMMY_UUID, List.of(damaged)))
            .as("one unreadable upload name must not fail the read - it is absent, and the caller shows the display name in its place")
            .isEmpty();
    }

    @Test
    void openNames_answersNothingForNoAttachments() {
        assertThat(AttachmentContent.openNames(Aes256Gcm.randomKey(), DUMMY_UUID, List.of()))
            .as("a day with no files is the common case and has nothing to open")
            .isEmpty();
    }

    private static SealedAttachment sealed(final byte[] dataKey, final UUID id, final String name) {
        return sealedUploadedAs(dataKey, id, name, name);
    }

    // Named rather than overloaded: two same-position parameters called different things read as a mistake, and one of them IS the difference.
    private static SealedAttachment sealedUploadedAs(final byte[] dataKey, final UUID id, final String name, final String fileName) {
        return new SealedAttachment(id, DAY, AttachmentContent.sealName(dataKey, DUMMY_UUID, DAY, id, name),
            AttachmentContent.sealFileName(dataKey, DUMMY_UUID, DAY, id, fileName), FILE.length);
    }
}
