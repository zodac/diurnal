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

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * One attachment's id paired with its sealed bytes — the projection {@link NoteAttachment#contentForUser(UUID)} reads, so an export opens every
 * file in the account from one query rather than one round trip per attachment. A typed projection produced by a JPQL {@code SELECT new ...}
 * constructor expression, never a positional {@code Object[]} tuple — the {@link SealedAttachment} pattern.
 *
 * <p>
 * Nothing else needs this shape: every other reader either wants the metadata ({@link SealedAttachment}, which deliberately leaves the bytes out)
 * or a single file's bytes ({@link NoteAttachment#sealedContent(UUID, UUID)}).
 *
 * <p>
 * Holding an array, it carries the identity {@code equals}/{@code hashCode} a record gives an array component; compare the opened bytes, or the
 * decrypted file, rather than two instances.
 *
 * @param id                the attachment's id
 * @param contentEncrypted the sealed bytes, opened by {@link AttachmentContent#openFile(byte[], UUID, java.time.LocalDate, UUID, byte[])}
 */
public record SealedAttachmentContent(UUID id, byte[] contentEncrypted) {

    // Hand-written for the same reason SealedAttachment is: a record's generated equals/hashCode compares a byte[] by IDENTITY, so two projections
    // of the same stored row would not be equal.
    @Override
    public boolean equals(final Object other) {
        if (!(other instanceof SealedAttachmentContent(final UUID otherId, final byte[] otherContent))) {
            return false;
        }
        return Objects.equals(id, otherId) && Arrays.equals(contentEncrypted, otherContent);
    }

    @Override
    public int hashCode() {
        return (31 * Objects.hashCode(id)) + Arrays.hashCode(contentEncrypted);
    }

    @Override
    public String toString() {
        return "SealedAttachmentContent{" + "id=" + id + ", contentEncrypted.length=" + contentEncrypted.length + '}';
    }
}
