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

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HKDF (RFC 5869) over HMAC-SHA-256, used to turn input that is <strong>already</strong> high-entropy into a key for a specific purpose.
 *
 * <p>
 * This is deliberately <strong>not</strong> a password hash and must never be used on one: it is fast by design, so it offers no resistance to
 * guessing. It suits the session token — 32 bytes straight from a {@link java.security.SecureRandom} ({@code SessionTokens.generate}) — where there
 * is nothing to guess and the only job is to derive a distinct key per purpose. Anything derived from a human-chosen secret goes through
 *
 * <p>
 * The {@code info} parameter provides <strong>domain separation</strong>: two different purposes derived from the same input produce unrelated keys,
 * so a key that leaks in one role cannot be used in another.
 *
 * <p>
 * Only the {@code L == HashLen} case of the specification is implemented — every key in this application is exactly {@link Aes256Gcm#KEY_BYTES} long,
 * which is the HMAC-SHA-256 output size, so the expand step is a single round and needs no counter loop.
 */
public final class Hkdf {

    private static final String MAC_ALGORITHM = "HmacSHA256";

    // RFC 5869 allows any salt for the extract step, including a constant; what matters is that it is
    // application-specific, so key material derived here cannot collide with another system's.
    private static final byte[] EXTRACT_SALT = "diurnal-hkdf-v1".getBytes(StandardCharsets.UTF_8);

    private Hkdf() {

    }

    /**
     * Derives a {@link Aes256Gcm#KEY_BYTES}-byte key from high-entropy input, bound to {@code info} so that the same input yields an unrelated key
     * for each distinct purpose.
     *
     * @param inputKeyMaterial the high-entropy input (never a password or passphrase)
     * @param info             the purpose label providing domain separation
     * @return the derived key
     */
    public static byte[] deriveKey(final byte[] inputKeyMaterial, final String info) {
        final byte[] pseudoRandomKey = hmac(EXTRACT_SALT, inputKeyMaterial);

        // The expand step's first (and here only) block is HMAC(PRK, info || 0x01) - the trailing counter byte
        // is mandated by RFC 5869 even when a single block satisfies the requested length.
        final byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
        final byte[] block = new byte[infoBytes.length + 1];
        System.arraycopy(infoBytes, 0, block, 0, infoBytes.length);
        block[infoBytes.length] = 1;

        return hmac(pseudoRandomKey, block);
    }

    private static byte[] hmac(final byte[] key, final byte[] message) {
        try {
            final Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
            return mac.doFinal(message);
        } catch (final InvalidKeyException | NoSuchAlgorithmException e) {
            // HMAC-SHA-256 is a mandated JDK algorithm, so this is unreachable in practice
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
