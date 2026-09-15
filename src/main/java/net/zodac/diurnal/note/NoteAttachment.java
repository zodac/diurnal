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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.persistence.AuditedEntity;
import net.zodac.diurnal.persistence.JpqlQuery;
import org.jspecify.annotations.Nullable;

/**
 * One file attached to a day's note. A day may hold several, and the note's own text says where each one sits by carrying a {@code [[name]]} token
 * for it (see {@link NoteTokens}) — so this table holds what the token names, and the note holds where it is.
 *
 * <p>
 * <strong>An attachment belongs to a DAY, not to a note row.</strong> The two are keyed the same way ({@code user_id}, {@code note_date}) rather than
 * by a foreign key, because a file can legitimately be attached to a day that has no note text yet: the first upload onto an empty day writes the
 * attachment, and the token it inserts is what the note then holds when the user saves it. {@code NoteService} keeps the two ends together —
 * clearing a day's note removes its attachments, and saving a note removes any attachment no token names any more.
 *
 * <p>
 * <strong>Every secret part is sealed under the owner's notes data key</strong>, the same key the note itself is sealed with, and bound to the owner,
 * the day, this row's id and which part it is; see {@link AttachmentContent}. Only the size stays readable, so a listing can be rendered without
 * opening anything.
 *
 * <p>
 * <strong>There are TWO names, and they are not the same thing.</strong> The display name is what the user calls the file and what the note's token
 * addresses, so a rename rewrites it; the file name is what was uploaded, extension and all, and nothing ever rewrites it.
 *
 * <p>
 * The id is <strong>assigned by the application</strong> rather than defaulted by the database, because it is bound into both seals: the value has to
 * exist before either half can be sealed, which a generated one would not.
 */
@Entity
@Table(name = "note_attachments")
public class NoteAttachment extends AuditedEntity {

    @Id
    @Column(name = "id", nullable = false)
    public UUID id;

    @Column(name = "user_id", nullable = false)
    public UUID userId;

    @Column(name = "note_date", nullable = false)
    public LocalDate noteDate;

    // The display name, sealed. A filename is the note's content by another route ("divorce-papers.pdf"), so it is protected exactly as the note is
    // - which is why a day's names are held apart by NoteAttachmentService rather than by a UNIQUE index. See AttachmentContent.
    @Column(name = "display_name_encrypted", nullable = false)
    public byte[] displayNameEncrypted;

    // The name the file was uploaded under, sealed under its own purpose so it cannot be confused with the display name above. Written once, at
    // upload, and never rewritten - a rename touches only the display name.
    @Column(name = "file_name_encrypted", nullable = false)
    public byte[] fileNameEncrypted;

    // The file itself, sealed under its own purpose so neither name's ciphertext can be pasted over it. The largest column the application stores,
    // and deliberately never selected by a listing - see NoteAttachmentQueries.
    @Column(name = "content_encrypted", nullable = false)
    public byte[] contentEncrypted;

    // The file's readable size, in the clear so a listing can show it without opening anything. Metadata of the same order as note_date, which the
    // calendar has always read plainly.
    @Column(name = "byte_size", nullable = false)
    public int byteSize;

    // ── Queries ───────────────────────────────────────────────────────────

    /**
     * Returns one day's attachments as {@link SealedAttachment} projections, in the order they were attached. Days with none answer an empty list.
     *
     * @param userId the owning user
     * @param date   the day whose attachments to read
     * @return the day's attachments, oldest first
     */
    public static List<SealedAttachment> sealedForUserAndDate(final UUID userId, final LocalDate date) {
        return JpqlQuery.of(NoteAttachmentQueries.SEALED_FOR_DATE_JPQL, SealedAttachment.class)
            .bind(NoteAttachmentQueries.USER_ID, userId)
            .bind(NoteAttachmentQueries.DATE, date)
            .resultList();
    }

    /**
     * Returns every attachment the user holds as {@link SealedAttachment} projections, by day and then by when each was attached — the order an
     * export writes them in.
     *
     * @param userId the owning user
     * @return the user's attachments, oldest day first
     */
    public static List<SealedAttachment> sealedForUser(final UUID userId) {
        return JpqlQuery.of(NoteAttachmentQueries.SEALED_FOR_USER_JPQL, SealedAttachment.class)
            .bind(NoteAttachmentQueries.USER_ID, userId)
            .resultList();
    }

    /**
     * Returns one attachment of the user's as a {@link SealedAttachment} projection, or {@code null} when they have none with that id.
     *
     * <p>
     * The owner is part of the query rather than a check the caller makes afterwards, so another account's attachment reads as absent rather than as
     * forbidden — the same answer an id that never existed gives, which is what keeps the two indistinguishable.
     *
     * @param userId the owning user
     * @param id     the attachment's id
     * @return the attachment, or {@code null} when the user has none with that id
     */
    @Nullable
    public static SealedAttachment sealedById(final UUID userId, final UUID id) {
        return JpqlQuery.of(NoteAttachmentQueries.SEALED_BY_ID_JPQL, SealedAttachment.class)
            .bind(NoteAttachmentQueries.USER_ID, userId)
            .bind(NoteAttachmentQueries.ID, id)
            .resultList()
            .stream()
            .findFirst()
            .orElse(null);
    }

    /**
     * Reads one attachment's sealed bytes, or {@code null} when the user has no attachment with that id — the only read that pays for the file.
     *
     * @param userId the owning user
     * @param id     the attachment's id
     * @return the sealed file, or {@code null} when the user has none with that id
     */
    public static byte @Nullable [] sealedContent(final UUID userId, final UUID id) {
        return JpqlQuery.of(NoteAttachmentQueries.CONTENT_BY_ID_JPQL, byte[].class)
            .bind(NoteAttachmentQueries.USER_ID, userId)
            .bind(NoteAttachmentQueries.ID, id)
            .resultList()
            .stream()
            .findFirst()
            .orElse(null);
    }

    /**
     * Returns every day the user has attached a file to, unordered — what decides which of the notes page's result rows show a paperclip.
     *
     * @param userId the owning user
     * @return the days holding at least one attachment
     */
    public static List<LocalDate> datesForUser(final UUID userId) {
        return JpqlQuery.of(NoteAttachmentQueries.DATES_JPQL, LocalDate.class)
            .bind(NoteAttachmentQueries.USER_ID, userId)
            .resultList();
    }

    // ── Mutations ─────────────────────────────────────────────────────────

    /**
     * Stores an attachment, whose every secret part is already sealed.
     *
     * @param userId               the owning user
     * @param date                 the day to attach it to
     * @param id                   the id bound into every seal, assigned by the caller before any was produced
     * @param displayNameEncrypted the sealed display name, which the note's token addresses
     * @param fileNameEncrypted    the sealed name the file was uploaded under, which nothing rewrites
     * @param contentEncrypted     the sealed bytes
     * @param byteSize             the file's readable size in bytes
     */
    public static void store(final UUID userId, final LocalDate date, final UUID id, final byte[] displayNameEncrypted,
        final byte[] fileNameEncrypted, final byte[] contentEncrypted, final int byteSize) {
        final NoteAttachment attachment = new NoteAttachment();
        attachment.id = id;
        attachment.userId = userId;
        attachment.noteDate = date;
        attachment.displayNameEncrypted = displayNameEncrypted;
        attachment.fileNameEncrypted = fileNameEncrypted;
        attachment.contentEncrypted = contentEncrypted;
        attachment.byteSize = byteSize;
        attachment.persist();
    }

    /**
     * Replaces one attachment's sealed display name, leaving the file untouched.
     *
     * <p>
     * Written as a bulk update rather than by loading the row, because loading it would detoast the whole file to rewrite a name — and would then
     * write the bytes back out again with it.
     *
     * @param userId               the owning user
     * @param id                   the attachment's id
     * @param displayNameEncrypted the new sealed display name
     * @return {@code true} when a row was updated, {@code false} when the user has no attachment with that id
     */
    public static boolean renameEntry(final UUID userId, final UUID id, final byte[] displayNameEncrypted) {
        return update("displayNameEncrypted = ?1, updatedAt = ?2 where userId = ?3 and id = ?4",
            displayNameEncrypted, Instant.now(), userId, id) > 0;
    }

    /**
     * Removes one of the user's attachments. Removing one that does not exist is a no-op.
     *
     * @param userId the owning user
     * @param id     the attachment's id
     * @return {@code true} when an attachment was removed, {@code false} when the user has none with that id
     */
    public static boolean deleteEntry(final UUID userId, final UUID id) {
        return delete("userId = ?1 and id = ?2", userId, id) > 0L;
    }

    /**
     * Removes every attachment on one of the user's days — what clearing that day's note takes with it.
     *
     * @param userId the owning user
     * @param date   the day to strip
     * @return how many attachments were removed
     */
    public static long deleteByUserAndDate(final UUID userId, final LocalDate date) {
        return delete("userId = ?1 and noteDate = ?2", userId, date);
    }

    /**
     * Removes every attachment belonging to a user in one statement, for the two paths that replace or remove a whole account's writing (a data
     * import, and deleting the account).
     *
     * @param userId the owning user whose attachments to remove
     */
    public static void deleteByUser(final UUID userId) {
        delete("userId = ?1", userId);
    }
}
