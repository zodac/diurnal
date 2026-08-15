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

package net.zodac.diurnal.note.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Seals a user's data key under the application's master key, and opens it again.
 *
 * <p>
 * This is envelope encryption: each user's notes are encrypted under a data key of their own, and only that 32-byte data key is protected by the
 * application's single configured key. Two things follow, and they are the reason for the indirection. Rotating the master key rewrites one small
 * row per user rather than every note ever written; and a change of scheme later — protecting the data key with something the user holds, say —
 * re-wraps those same rows and leaves every sealed note untouched.
 *
 * <p>
 * The wrapping key is not the configured value itself but an {@link Hkdf} derivation of it, so the same master key can grow other uses without any
 * of them sharing key material.
 *
 * <p>
 * Every wrapping is bound to its owner through the AEAD associated data, so a wrapped key lifted from one account's row cannot be opened against
 * another's.
 */
public final class DataKeyEnvelope {

    // Domain separation for the wrapping key derived from the configured master, so the same master cannot yield key
    // material for any other purpose it might later be put to.
    private static final String WRAPPING_KEY_INFO = "diurnal-notes-data-key-v1";

    private DataKeyEnvelope() {

    }

    /**
     * Seals a data key under the application's master key.
     *
     * @param dataKey the data key to wrap
     * @param masterKey the application's configured master key
     * @param owner the account the wrapping belongs to, bound in as associated data
     * @return the wrapped data key, for storage
     */
    public static byte[] wrap(final byte[] dataKey, final byte[] masterKey, final UUID owner) {
        return Aes256Gcm.seal(wrappingKey(masterKey), dataKey, ownerContext(owner));
    }

    /**
     * Opens a data key sealed by {@link #wrap}. An empty result means the master key is not the one the value was wrapped with — the case that
     * matters in practice, a changed or mistyped configured key — or that the stored row has been moved to another account.
     *
     * @param wrapped the wrapped data key
     * @param masterKey the application's configured master key
     * @param owner the account the wrapping belongs to
     * @return the data key, or empty when this master key does not open it
     */
    public static Optional<byte[]> unwrap(final byte[] wrapped, final byte[] masterKey, final UUID owner) {
        return Aes256Gcm.open(wrappingKey(masterKey), wrapped, ownerContext(owner));
    }

    private static byte[] wrappingKey(final byte[] masterKey) {
        return Hkdf.deriveKey(masterKey, WRAPPING_KEY_INFO);
    }

    private static byte[] ownerContext(final UUID owner) {
        return owner.toString().getBytes(StandardCharsets.UTF_8);
    }
}
