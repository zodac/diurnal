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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Aes256Gcm}: the round trip, and the four ways an open legitimately fails — a wrong key, mismatched associated data, a
 * modified ciphertext and a truncated blob.
 */
class Aes256GcmTest {

    private static final byte[] PLAINTEXT = "A journal entry, which must never be readable in the database.".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ASSOCIATED_DATA = "user-id|2026-08-06".getBytes(StandardCharsets.UTF_8);

    @Test
    void sealThenOpen_returnsTheOriginalPlaintext() {
        final byte[] key = Aes256Gcm.randomKey();

        final byte[] sealed = Aes256Gcm.seal(key, PLAINTEXT, ASSOCIATED_DATA);
        final Optional<byte[]> opened = Aes256Gcm.open(key, sealed, ASSOCIATED_DATA);

        assertThat(opened)
            .as("a value sealed and opened with the same key and associated data should round-trip unchanged")
            .contains(PLAINTEXT);
    }

    @Test
    void seal_doesNotLeaveThePlaintextInTheSealedValue() {
        final byte[] key = Aes256Gcm.randomKey();

        final byte[] sealed = Aes256Gcm.seal(key, PLAINTEXT, ASSOCIATED_DATA);

        assertThat(new String(sealed, StandardCharsets.UTF_8))
            .as("the sealed blob should not contain the plaintext it was made from")
            .doesNotContain("journal entry");
    }

    @Test
    void seal_usesFreshIvEachTime() {
        final byte[] key = Aes256Gcm.randomKey();

        final byte[] first = Aes256Gcm.seal(key, PLAINTEXT, ASSOCIATED_DATA);
        final byte[] second = Aes256Gcm.seal(key, PLAINTEXT, ASSOCIATED_DATA);

        assertThat(first)
            .as("sealing identical input twice should produce different blobs, because each seal generates its own IV")
            .isNotEqualTo(second);
    }

    @Test
    void open_refusesKeyThatDidNotSealTheValue() {
        final byte[] sealed = Aes256Gcm.seal(Aes256Gcm.randomKey(), PLAINTEXT, ASSOCIATED_DATA);

        assertThat(Aes256Gcm.open(Aes256Gcm.randomKey(), sealed, ASSOCIATED_DATA))
            .as("a different key should not open the value")
            .isEmpty();
    }

    @Test
    void open_refusesMismatchedAssociatedData() {
        final byte[] key = Aes256Gcm.randomKey();
        final byte[] sealed = Aes256Gcm.seal(key, PLAINTEXT, ASSOCIATED_DATA);

        assertThat(Aes256Gcm.open(key, sealed, "user-id|2026-08-07".getBytes(StandardCharsets.UTF_8)))
            .as("associated data binds a ciphertext to its context, so a note moved to another date should not open")
            .isEmpty();
    }

    @Test
    void open_refusesModifiedCiphertext() {
        final byte[] key = Aes256Gcm.randomKey();
        final byte[] sealed = Aes256Gcm.seal(key, PLAINTEXT, ASSOCIATED_DATA);
        final byte[] tampered = Arrays.copyOf(sealed, sealed.length);
        tampered[tampered.length - 1] ^= (byte) 0xFF;

        assertThat(Aes256Gcm.open(key, tampered, ASSOCIATED_DATA))
            .as("flipping a bit of the ciphertext should be detected by the authentication tag")
            .isEmpty();
    }

    @Test
    void open_refusesModifiedIv() {
        final byte[] key = Aes256Gcm.randomKey();
        final byte[] sealed = Aes256Gcm.seal(key, PLAINTEXT, ASSOCIATED_DATA);
        final byte[] tampered = Arrays.copyOf(sealed, sealed.length);
        tampered[0] ^= (byte) 0xFF;

        assertThat(Aes256Gcm.open(key, tampered, ASSOCIATED_DATA))
            .as("modifying the prefixed IV should be detected too")
            .isEmpty();
    }

    @Test
    void open_refusesBlobTooShortToHoldAnIv() {
        final byte[] key = Aes256Gcm.randomKey();

        assertThat(Aes256Gcm.open(key, new byte[12], ASSOCIATED_DATA))
            .as("a blob of exactly the IV length carries no ciphertext and should be refused before any cipher work")
            .isEmpty();
        assertThat(Aes256Gcm.open(key, new byte[0], ASSOCIATED_DATA))
            .as("an empty blob should be refused")
            .isEmpty();
    }

    @Test
    void sealThenOpen_handlesEmptyPlaintextAndEmptyAssociatedData() {
        final byte[] key = Aes256Gcm.randomKey();

        final byte[] sealed = Aes256Gcm.seal(key, new byte[0], new byte[0]);

        assertThat(Aes256Gcm.open(key, sealed, new byte[0]))
            .as("an empty plaintext should still round-trip, carrying only its IV and tag")
            .contains(new byte[0]);
    }

    @Test
    void randomKey_isTheExpectedLengthAndNotConstant() {
        final byte[] first = Aes256Gcm.randomKey();

        assertThat(first)
            .as("a generated key should be exactly AES-256's key size")
            .hasSize(Aes256Gcm.KEY_BYTES);
        assertThat(first)
            .as("two generated keys should differ")
            .isNotEqualTo(Aes256Gcm.randomKey());
    }

    @Test
    void randomBytes_returnsTheRequestedCount() {
        assertThat(Aes256Gcm.randomBytes(16))
            .as("randomBytes should return exactly the requested number of bytes")
            .hasSize(16);
        assertThat(Aes256Gcm.randomBytes(0))
            .as("a request for no bytes should return an empty array")
            .isEmpty();
    }
}
