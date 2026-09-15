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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.zodac.diurnal.note.crypto.Aes256Gcm;

/**
 * Seals and opens an attachment's secret parts — its bytes, plus the display name the note embeds it by and the name it was uploaded under — under
 * its owner's notes data key, the same key {@link NoteContent} seals the note itself with.
 *
 * <p>
 * <strong>Both names are sealed as well as the file.</strong> A filename is the note's content by another route: an operator reading
 * {@code note_attachments} in the clear would learn as much from {@code "divorce-papers.pdf"} as from the paragraph beside it, which is exactly what
 * encrypting {@code notes.content_encrypted} was built to prevent. The consequence is that no {@code UNIQUE} index can hold a day's names apart, so
 * {@code NoteAttachmentService} settles that over the handful of rows a day has instead.
 *
 * <p>
 * Each part is bound to the OWNER, the DATE, the attachment's own ID and which part it is, through the AEAD associated data — so a ciphertext lifted
 * out of the table fails to open anywhere but where it came from. The last of those is what stops the parts being swapped for one another: a name
 * pasted into the file column would otherwise open perfectly and be served as file bytes, and a display name pasted over a file name would quietly
 * undo a rename. The rest of the row (the owner, the date, the size) stays in the clear for the same reason a note's does, and an administrator can
 * edit those columns, so binding them into the seal is what makes doing so detectable rather than silently effective.
 *
 * <p>
 * Kept free of persistence and request state so the round trip is deterministically unit-testable.
 */
public final class AttachmentContent {

    private static final String NAME_PURPOSE = "name";
    private static final String FILE_NAME_PURPOSE = "filename";
    private static final String FILE_PURPOSE = "file";

    private AttachmentContent() {

    }

    /**
     * Seals an attachment's display name.
     *
     * @param dataKey the owner's notes data key
     * @param userId  the owning user, bound into the seal
     * @param date    the day the attachment belongs to, bound into the seal
     * @param id      the attachment's own id, bound into the seal
     * @param name    the normalised display name to seal
     * @return the sealed form to store
     */
    public static byte[] sealName(final byte[] dataKey, final UUID userId, final LocalDate date, final UUID id, final String name) {
        return Aes256Gcm.seal(dataKey, name.getBytes(StandardCharsets.UTF_8), associatedData(userId, date, id, NAME_PURPOSE));
    }

    /**
     * Opens a sealed display name, returning empty when this key does not open it or when the stored row has been moved to another owner, day or id.
     *
     * @param dataKey the owner's notes data key
     * @param userId  the owning user the stored row claims
     * @param date    the day the stored row claims
     * @param id      the attachment's own id
     * @param sealed  the stored sealed name
     * @return the readable display name, or empty when this key does not open it
     */
    public static Optional<String> openName(final byte[] dataKey, final UUID userId, final LocalDate date, final UUID id, final byte[] sealed) {
        return Aes256Gcm.open(dataKey, sealed, associatedData(userId, date, id, NAME_PURPOSE))
            .map(plaintext -> new String(plaintext, StandardCharsets.UTF_8));
    }

    /**
     * Opens the display names of a whole day's attachments under one data key, keeping the order they were handed in and holding only the ones that
     * could be read.
     *
     * <p>
     * Every path that needs a day's names needs all of them at once — listing the card, finding a free name for an upload, and deciding which stored
     * files the note no longer embeds — so this is the shape they are read in. A row that will not open is omitted rather than failing the day: one
     * damaged attachment must not take down the note it belongs to, exactly as one damaged note must not take down a month of calendar.
     *
     * @param dataKey     the owner's notes data key
     * @param userId      the owning user, whose id is bound into each seal
     * @param attachments the day's stored attachments
     * @return the readable display name of each, by id, in the given order
     */
    public static Map<UUID, String> openNames(final byte[] dataKey, final UUID userId, final List<SealedAttachment> attachments) {
        final Map<UUID, String> byId = new LinkedHashMap<>();
        for (final SealedAttachment attachment : attachments) {
            openName(dataKey, userId, attachment.noteDate(), attachment.id(), attachment.displayNameEncrypted())
                .ifPresent(name -> byId.put(attachment.id(), name));
        }
        return byId;
    }

    /**
     * Seals the name an attachment was uploaded under.
     *
     * <p>
     * Sealed separately from the display name rather than sharing its ciphertext, under a purpose of its own: the two are equal for most
     * attachments, and binding them apart is what keeps a copy of one from passing as the other.
     *
     * @param dataKey  the owner's notes data key
     * @param userId   the owning user, bound into the seal
     * @param date     the day the attachment belongs to, bound into the seal
     * @param id       the attachment's own id, bound into the seal
     * @param fileName the sanitised name the upload arrived under
     * @return the sealed form to store
     */
    public static byte[] sealFileName(final byte[] dataKey, final UUID userId, final LocalDate date, final UUID id, final String fileName) {
        return Aes256Gcm.seal(dataKey, fileName.getBytes(StandardCharsets.UTF_8), associatedData(userId, date, id, FILE_NAME_PURPOSE));
    }

    /**
     * Opens a sealed upload name, returning empty when this key does not open it, or when the stored row has been moved to another owner, day or id.
     *
     * @param dataKey the owner's notes data key
     * @param userId  the owning user the stored row claims
     * @param date    the day the stored row claims
     * @param id      the attachment's own id
     * @param sealed  the stored sealed upload name
     * @return the readable upload name, or empty when this key does not open it
     */
    public static Optional<String> openFileName(final byte[] dataKey, final UUID userId, final LocalDate date, final UUID id, final byte[] sealed) {
        return Aes256Gcm.open(dataKey, sealed, associatedData(userId, date, id, FILE_NAME_PURPOSE))
            .map(plaintext -> new String(plaintext, StandardCharsets.UTF_8));
    }

    /**
     * Opens the upload names of a whole day's — or a whole account's — attachments under one data key, keeping the order they were handed in and
     * holding only the ones that could be read.
     *
     * <p>
     * The counterpart of {@link #openNames(byte[], UUID, List)}, and separate from it because most callers want only the display name: a row is
     * addressed by the note's token, and the upload name is needed by the two surfaces that SHOW a file (the attachments table and the API) rather
     * than by the ones that rewrite a note. A row whose upload name will not open is simply absent - the caller shows the display name in its
     * place, exactly as {@link #openNames(byte[], UUID, List)}'s own damaged rows are skipped rather than failing the day.
     *
     * @param dataKey     the owner's notes data key
     * @param userId      the owning user, whose id is bound into each seal
     * @param attachments the stored attachments
     * @return the readable upload name of each, by id, in the given order
     */
    public static Map<UUID, String> openFileNames(final byte[] dataKey, final UUID userId, final List<SealedAttachment> attachments) {
        final Map<UUID, String> byId = new LinkedHashMap<>();
        for (final SealedAttachment attachment : attachments) {
            openFileName(dataKey, userId, attachment.noteDate(), attachment.id(), attachment.fileNameEncrypted())
                .ifPresent(fileName -> byId.put(attachment.id(), fileName));
        }
        return byId;
    }

    /**
     * Seals an attachment's bytes.
     *
     * @param dataKey the owner's notes data key
     * @param userId  the owning user, bound into the seal
     * @param date    the day the attachment belongs to, bound into the seal
     * @param id      the attachment's own id, bound into the seal
     * @param file    the plaintext bytes of the file
     * @return the sealed form to store
     */
    public static byte[] sealFile(final byte[] dataKey, final UUID userId, final LocalDate date, final UUID id, final byte[] file) {
        return Aes256Gcm.seal(dataKey, file, associatedData(userId, date, id, FILE_PURPOSE));
    }

    /**
     * Opens an attachment's sealed bytes, returning empty when this key does not open them, or when the stored row has been moved to another owner,
     * day or id.
     *
     * @param dataKey the owner's notes data key
     * @param userId  the owning user the stored row claims
     * @param date    the day the stored row claims
     * @param id      the attachment's own id
     * @param sealed  the stored sealed file
     * @return the readable bytes, or empty when this key does not open them
     */
    public static Optional<byte[]> openFile(final byte[] dataKey, final UUID userId, final LocalDate date, final UUID id, final byte[] sealed) {
        return Aes256Gcm.open(dataKey, sealed, associatedData(userId, date, id, FILE_PURPOSE));
    }

    private static byte[] associatedData(final UUID userId, final LocalDate date, final UUID id, final String purpose) {
        return (userId + "|" + date + "|" + id + "|" + purpose).getBytes(StandardCharsets.UTF_8);
    }
}
