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

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored attachment as every path except a download actually uses it: its id, the day it belongs to, its two sealed names and its size. A typed
 * projection produced by {@link NoteAttachment}'s {@code sealed*} finders through a JPQL {@code SELECT new ...} constructor expression, never a
 * positional {@code Object[]} tuple — the {@link SealedNote} pattern.
 *
 * <p>
 * <strong>The FILE is deliberately absent.</strong> Listing a day's attachments, renaming one and deleting one all need the name and nothing else,
 * and the bytes are the largest thing this application stores — a row's worth of them is up to the whole per-request body limit. Selecting them into
 * every list would detoast a megabyte per attachment to render a name.
 *
 * <p>
 * The day is carried because opening EITHER half needs it: {@link AttachmentContent} binds the owner, the day and the id into the associated data,
 * so a projection read by id still knows enough to be opened. The owner is not, for the same reason {@link SealedNote} omits it — every query
 * filters on a single {@code :userId}, which the caller then passes to the key.
 *
 * <p>
 * Holding an array, it carries the identity {@code equals}/{@code hashCode} a record gives an array component; compare the opened name, or the bytes
 * themselves, rather than two instances.
 *
 * @param id                   the attachment's id
 * @param noteDate             the day the attachment belongs to
 * @param displayNameEncrypted the sealed display name, opened by
 *                             {@link AttachmentContent#openName(byte[], UUID, LocalDate, UUID, byte[])}
 * @param fileNameEncrypted    the sealed name the file was uploaded under, opened by
 *                             {@link AttachmentContent#openFileName(byte[], UUID, LocalDate, UUID, byte[])}
 * @param byteSize             the size of the stored file in bytes, held in the clear so a listing can show it without opening anything
 */
public record SealedAttachment(UUID id, LocalDate noteDate, byte[] displayNameEncrypted, byte[] fileNameEncrypted, int byteSize) {

    // Hand-written because of the array component: a record's generated equals/hashCode compare a byte[] by IDENTITY, so two projections of the same
    // stored row would not be equal. Narrowed with a pattern rather than a cast (CODE_STYLE.md), and the hash is written out rather than going
    // through Objects.hash, whose varargs allocate an array on a method that runs per row.
    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof SealedAttachment(final UUID otherId, final LocalDate otherDate, final byte[] otherName, final byte[] otherFileName,
            final int otherSize))) {
            return false;
        }
        return Objects.equals(id, otherId) && Objects.equals(noteDate, otherDate) && Arrays.equals(displayNameEncrypted, otherName)
            && Arrays.equals(fileNameEncrypted, otherFileName) && byteSize == otherSize;
    }

    @Override
    public int hashCode() {
        final int names = (31 * Arrays.hashCode(displayNameEncrypted)) + (31 * Arrays.hashCode(fileNameEncrypted));
        return (31 * ((31 * Objects.hashCode(id)) + Objects.hashCode(noteDate))) + names + byteSize;
    }

    @Override
    public String toString() {
        return "SealedAttachment{" + "id=" + id + ", noteDate=" + noteDate + ", displayNameEncrypted.length=" + displayNameEncrypted.length
            + ", fileNameEncrypted.length=" + fileNameEncrypted.length + ", byteSize=" + byteSize + '}';
    }
}
