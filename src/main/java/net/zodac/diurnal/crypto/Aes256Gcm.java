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

package net.zodac.diurnal.crypto;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Optional;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The one authenticated-encryption primitive in the application: AES-256 in GCM mode, used both to encrypt a note's content under its owner's data
 * key and to wrap that data key under each of the keys that may unlock it.
 *
 * <p>
 * GCM is an AEAD mode, so every {@link #seal(byte[], byte[], byte[])} produces a ciphertext that cannot be modified without detection, and
 * {@link #open(byte[], byte[], byte[])} reports tampering rather than returning corrupted plaintext. The <strong>associated data</strong> parameter
 * is authenticated but not encrypted, which is what lets a caller bind a ciphertext to its context — a note is sealed against its
 * {@code user_id || note_date}, so a stored ciphertext moved to another day, or to another user's row, fails to open instead of silently decrypting
 * in the wrong place.
 *
 * <p>
 * A fresh 12-byte initialisation vector is generated for every seal and carried as the first 12 bytes of the returned value, so the caller stores a
 * single opaque blob rather than two columns. Reusing an IV under one key destroys GCM's guarantees entirely, which is why no method here accepts a
 * caller-supplied one.
 *
 * <p>
 * Kept free of any persistence or request state so the branching is deterministically unit-testable.
 */
public final class Aes256Gcm {

    /**
     * The key size this primitive works in, in bytes (256 bits). Every data key and every wrapping key is exactly this long.
     */
    public static final int KEY_BYTES = 32;

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Aes256Gcm() {

    }

    /**
     * Encrypts {@code plaintext} under {@code key}, authenticating {@code associatedData} alongside it, and returns the freshly-generated IV
     * concatenated with the ciphertext and its authentication tag. The associated data is not stored — the caller must supply the identical value to
     * {@link #open(byte[], byte[], byte[])} or the open fails.
     *
     * @param key            the {@value #KEY_BYTES}-byte encryption key
     * @param plaintext      the bytes to encrypt
     * @param associatedData the context to authenticate but not encrypt, binding the ciphertext to where it is stored
     * @return the sealed blob: 12 bytes of IV, followed by the ciphertext and tag
     */
    public static byte[] seal(final byte[] key, final byte[] plaintext, final byte[] associatedData) {
        final byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);

        try {
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            final byte[] ciphertext = cipher.doFinal(plaintext);

            final byte[] sealed = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv, 0, sealed, 0, IV_BYTES);
            System.arraycopy(ciphertext, 0, sealed, IV_BYTES, ciphertext.length);
            return sealed;
        } catch (final BadPaddingException | IllegalBlockSizeException | InvalidAlgorithmParameterException | InvalidKeyException
                       | NoSuchAlgorithmException | NoSuchPaddingException e) {
            // AES/GCM is a mandated JDK transformation and the key length is fixed by the caller contract, so a failure here is a programming
            // error rather than a runtime case. The message deliberately carries no key, plaintext or associated data.
            throw new IllegalStateException("Unable to seal value", e);
        }
    }

    /**
     * Decrypts a blob produced by {@link #seal(byte[], byte[], byte[])}, verifying its authentication tag against {@code associatedData}. Returns an
     * empty {@link Optional} when the key is wrong, the associated data does not match, or the blob has been truncated or modified — all four are the
     * same answer to the caller ("this key does not open this value"), and none is distinguishable from the others by design.
     *
     * @param key            the {@value #KEY_BYTES}-byte decryption key
     * @param sealed         the blob to open, as returned by {@code seal}
     * @param associatedData the exact context supplied when the value was sealed
     * @return the decrypted bytes, or empty when the value does not open
     */
    public static Optional<byte[]> open(final byte[] key, final byte[] sealed, final byte[] associatedData) {
        // A blob too short to hold an IV needs no length guard of its own: copyOf zero-pads it, the ciphertext slice comes
        // out empty, and GCM then rejects it for a missing tag - the same empty answer, through the same path as every
        // other failure. An explicit short-circuit would only add a branch whose boundary no behaviour can distinguish.
        final byte[] iv = Arrays.copyOf(sealed, IV_BYTES);
        final byte[] ciphertext = Arrays.copyOfRange(sealed, Math.min(IV_BYTES, sealed.length), sealed.length);

        try {
            final Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, KEY_ALGORITHM), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            return Optional.of(cipher.doFinal(ciphertext));
        } catch (final BadPaddingException | IllegalBlockSizeException | InvalidAlgorithmParameterException | InvalidKeyException
                       | NoSuchAlgorithmException | NoSuchPaddingException e) {
            // The expected failure path, not an error: a wrong passphrase reaches here on every mistyped attempt. Swallowed deliberately - the
            // exception distinguishes a bad tag from a bad key, and surfacing that difference would leak which one the caller got wrong.
            return Optional.empty();
        }
    }

    /**
     * Generates a fresh, random {@value #KEY_BYTES}-byte key. Used to mint a user's data key, which is then wrapped under each key that may unlock it
     * and never stored in the clear.
     *
     * @return a new random key
     */
    public static byte[] randomKey() {
        return randomBytes(KEY_BYTES);
    }

    /**
     * Generates {@code count} cryptographically random bytes, used for the per-user key-derivation salts.
     *
     * @param count how many bytes to generate
     * @return the random bytes
     */
    public static byte[] randomBytes(final int count) {
        final byte[] bytes = new byte[count];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
