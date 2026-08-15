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
import java.util.Optional;
import java.util.UUID;
import net.zodac.diurnal.note.crypto.Aes256Gcm;

/**
 * Seals and opens a single note's content under its owner's data key.
 *
 * <p>
 * Each note is bound to <strong>both</strong> its owner and its date through the AEAD associated data, so a ciphertext lifted out of the table and
 * written back against a different day, or into another account's row, fails to open rather than decrypting somewhere it does not belong. That
 * matters because the rest of the row — the date, the owner — stays in the clear for the calendar and statistics to read, and an administrator can
 * edit those columns freely; binding them into the seal means doing so is detected rather than silently effective.
 *
 * <p>
 * Kept free of persistence and request state so the round trip is deterministically unit-testable.
 */
public final class NoteContent {

    private NoteContent() {

    }

    /**
     * Seals a note's normalised content under the owner's data key, bound to the owner and the date it belongs to.
     *
     * @param dataKey the owner's notes data key
     * @param userId the owning user, bound into the seal
     * @param date the day the note belongs to, bound into the seal
     * @param content the normalised content to seal
     * @return the sealed form to store
     */
    public static byte[] seal(final byte[] dataKey, final UUID userId, final LocalDate date, final String content) {
        return Aes256Gcm.seal(dataKey, content.getBytes(StandardCharsets.UTF_8), associatedData(userId, date));
    }

    /**
     * Opens a sealed note, returning empty when the data key does not open it or when the stored row has been moved to another owner or date.
     *
     * @param dataKey the owner's notes data key
     * @param userId the owning user the stored row claims
     * @param date the day the stored row claims
     * @param sealed the stored sealed form
     * @return the readable content, or empty when this key does not open it
     */
    public static Optional<String> open(final byte[] dataKey, final UUID userId, final LocalDate date, final byte[] sealed) {
        return Aes256Gcm.open(dataKey, sealed, associatedData(userId, date))
            .map(plaintext -> new String(plaintext, StandardCharsets.UTF_8));
    }

    private static byte[] associatedData(final UUID userId, final LocalDate date) {
        return (userId + "|" + date).getBytes(StandardCharsets.UTF_8);
    }
}
