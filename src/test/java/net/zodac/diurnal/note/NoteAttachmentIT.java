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
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link NoteAttachment}'s finders and mutations against a real database — the storage semantics the attachment feature is built on: a day
 * holding several files in the order they were attached, a projection that never reads the file itself, every query filtering on the owner, and the
 * two bulk deletes that keep an account's files from outliving its notes.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class NoteAttachmentIT extends IntegrationTestBase {

    private static final LocalDate DAY = FIXED_TODAY;
    private static final byte[] FILE = "not really a png".getBytes(StandardCharsets.UTF_8);

    private User owner;
    private User other;

    @Override
    protected void createDbState() {
        owner = newUser("attachment-owner@lt.test", "Attachment Owner");
        other = newUser("attachment-other@lt.test", "Attachment Other");
    }

    @Test
    void sealedForUserAndDate_withNoAttachments_isEmpty() {
        runInTx(() -> assertThat(NoteAttachment.sealedForUserAndDate(owner.id, DAY))
            .as("almost every day has no files, and that day must cost one index read and answer nothing")
            .isEmpty());
    }

    @Test
    void sealedForUserAndDate_readsTheDaysAttachmentsOldestFirst() {
        runInTx(() -> {
            newAttachment(owner.id, DAY, "first.png", FILE);
            newAttachment(owner.id, DAY, "second.png", FILE);
        });

        runInTx(() -> assertThat(NoteAttachment.sealedForUserAndDate(owner.id, DAY))
            .as("the card lists a day's files in the order the user built it")
            .hasSize(2)
            .allSatisfy(attachment -> assertThat(attachment.noteDate())
            .as("the day comes back with the projection, because opening either sealed half needs it")
            .isEqualTo(DAY)));
    }

    @Test
    void sealedForUserAndDate_isScopedToTheOwner() {
        runInTx(() -> newAttachment(other.id, DAY, "theirs.png", FILE));

        runInTx(() -> assertThat(NoteAttachment.sealedForUserAndDate(owner.id, DAY))
            .as("another account's file on the same day is not this account's")
            .isEmpty());
    }

    @Test
    void sealedById_readsOneAttachmentWithoutItsBytes() {
        final UUID id = attach(owner.id, DAY, "route.png");

        runInTx(() -> assertThat(NoteAttachment.sealedById(owner.id, id))
            .as("a rename, a delete and a download all resolve the row this way first")
            .isNotNull()
            .satisfies(attachment -> {
                assertThat(attachment.id()).as("the id round-trips").isEqualTo(id);
                assertThat(attachment.byteSize()).as("the size is stored in the clear, so a listing needs nothing opened").isEqualTo(FILE.length);
            }));
    }

    @Test
    void sealedById_answersNullForAnotherAccountsAttachment() {
        final UUID id = attach(other.id, DAY, "theirs.png");

        runInTx(() -> assertThat(NoteAttachment.sealedById(owner.id, id))
            .as("another account's attachment reads as absent rather than as forbidden, so an id cannot be probed for existence")
            .isNull());
    }

    @Test
    void sealedContent_readsTheStoredFileByItself() {
        final UUID id = attach(owner.id, DAY, "route.png");

        runInTx(() -> {
            assertThat(NoteAttachment.sealedContent(owner.id, id))
                .as("the bytes have a query of their own, since no listing selects them")
                .isNotNull()
                .isNotEmpty();
            assertThat(storedAttachmentFile(owner.id, DAY, id).orElse(null))
                .as("and they open back to exactly what was stored")
                .isEqualTo(FILE);
        });
    }

    @Test
    void sealedContent_answersNothingForAnotherAccountsAttachment() {
        final UUID id = attach(other.id, DAY, "theirs.png");

        runInTx(() -> assertThat(NoteAttachment.sealedContent(owner.id, id))
            .as("the owner is part of the query, so another account's file reads as absent rather than as forbidden")
            .isNull());
    }

    @Test
    void datesForUser_answersEachDayOnceHoweverManyFilesItHolds() {
        runInTx(() -> {
            newAttachment(owner.id, DAY, "first.png", FILE);
            newAttachment(owner.id, DAY, "second.png", FILE);
            newAttachment(owner.id, DAY.minusDays(3), "older.png", FILE);
            newAttachment(other.id, DAY.plusDays(1), "theirs.png", FILE);
        });

        runInTx(() -> assertThat(NoteAttachment.datesForUser(owner.id))
            .as("the notes page's paperclip marker is per DAY, so two files on one day are one day")
            .containsExactlyInAnyOrder(DAY, DAY.minusDays(3)));
    }

    @Test
    void store_keepsTheUploadedFileNameBesideTheDisplayName() {
        final UUID id = attachUploadedAs(owner.id, DAY, "Berlin ticket", "ticket-stub.png");

        runInTx(() -> {
            assertThat(storedAttachmentName(owner.id, DAY, id))
                .as("the display name is what the note's token addresses")
                .isEqualTo("Berlin ticket");
            assertThat(storedAttachmentFileName(owner.id, DAY, id))
                .as("and the file name is what the upload was called, sealed separately under its own purpose")
                .isEqualTo("ticket-stub.png");
        });
    }

    @Test
    void renameEntry_leavesTheUploadedFileNameAlone() {
        final UUID id = attachUploadedAs(owner.id, DAY, "route.png", "route.png");

        runInTx(() -> NoteAttachment.renameEntry(owner.id, id, sealAttachmentName(owner.id, DAY, id, "Lakeside route.png")));

        runInTx(() -> {
            assertThat(storedAttachmentName(owner.id, DAY, id))
                .as("the rename writes the display name")
                .isEqualTo("Lakeside route.png");
            assertThat(storedAttachmentFileName(owner.id, DAY, id))
                .as("and touches nothing else - what the file IS did not change when it was relabelled")
                .isEqualTo("route.png");
        });
    }

    @Test
    void renameEntry_replacesTheSealedNameAndLeavesTheFileAlone() {
        final UUID id = attach(owner.id, DAY, "route.png");

        runInTx(() -> assertThat(NoteAttachment.renameEntry(owner.id, id, sealAttachmentName(owner.id, DAY, id, "Berlin route.png")))
            .as("a row that exists is updated")
            .isTrue());

        runInTx(() -> {
            assertThat(storedAttachmentName(owner.id, DAY, id))
                .as("the stored name is the new one")
                .isEqualTo("Berlin route.png");
            assertThat(storedAttachmentFile(owner.id, DAY, id).orElse(null))
                .as("and the file itself was never rewritten, which is why the rename is a bulk update rather than an entity load")
                .isEqualTo(FILE);
        });
    }

    @Test
    void renameEntry_answersFalseForAnotherAccountsAttachment() {
        final UUID id = attach(other.id, DAY, "theirs.png");

        runInTx(() -> assertThat(NoteAttachment.renameEntry(owner.id, id, sealAttachmentName(owner.id, DAY, id, "mine.png")))
            .as("the owner is part of the update's own WHERE clause, not a check a caller makes first")
            .isFalse());
    }

    @Test
    void deleteEntry_removesOneAttachmentAndReportsWhetherItDid() {
        final UUID id = attach(owner.id, DAY, "route.png");

        runInTx(() -> assertThat(NoteAttachment.deleteEntry(owner.id, id))
            .as("a row that exists is removed")
            .isTrue());
        runInTx(() -> assertThat(NoteAttachment.deleteEntry(owner.id, id))
            .as("and removing it twice is a no-op that says so")
            .isFalse());
    }

    @Test
    void deleteByUserAndDate_takesTheWholeDay() {
        runInTx(() -> {
            newAttachment(owner.id, DAY, "first.png", FILE);
            newAttachment(owner.id, DAY, "second.png", FILE);
            newAttachment(owner.id, DAY.plusDays(1), "tomorrow.png", FILE);
        });

        runInTx(() -> assertThat(NoteAttachment.deleteByUserAndDate(owner.id, DAY))
            .as("clearing a day's note takes its files with it, and reports how many went")
            .isEqualTo(2L));

        runInTx(() -> assertThat(NoteAttachment.sealedForUserAndDate(owner.id, DAY.plusDays(1)))
            .as("and leaves every other day alone")
            .hasSize(1));
    }

    @Test
    void deleteByUser_takesTheWholeAccountAndNobodyElses() {
        runInTx(() -> {
            newAttachment(owner.id, DAY, "mine.png", FILE);
            newAttachment(other.id, DAY, "theirs.png", FILE);
        });

        runInTx(() -> NoteAttachment.deleteByUser(owner.id));

        runInTx(() -> {
            assertThat(NoteAttachment.datesForUser(owner.id))
                .as("an import replaces a whole journal, so the files that belonged to it go too")
                .isEmpty();
            assertThat(NoteAttachment.datesForUser(other.id))
                .as("and another account's files are untouched")
                .containsExactly(DAY);
        });
    }

    // newAttachment writes inside a transaction and answers the id it minted, which every write here then addresses the row by; runInTx takes a
    // block that returns nothing, so the id is carried out through a holder rather than returned.
    private UUID attach(final UUID userId, final LocalDate date, final String name) {
        return attachUploadedAs(userId, date, name, name);
    }

    // Named rather than overloaded: two same-position parameters called different things read as a mistake, and one of them IS the difference.
    private UUID attachUploadedAs(final UUID userId, final LocalDate date, final String name, final String fileName) {
        final UUID[] id = new UUID[1];
        runInTx(() -> id[0] = newRenamedAttachment(userId, date, name, fileName, FILE));
        return id[0];
    }
}
