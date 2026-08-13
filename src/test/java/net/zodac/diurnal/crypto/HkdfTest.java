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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Hkdf}: that a derivation is reproducible (which is what lets a stored wrapped key be reopened on a later request), and that
 * the purpose label genuinely separates one derived key from another.
 */
class HkdfTest {

    private static final byte[] TOKEN = "a-32-byte-session-token-value!!!".getBytes(StandardCharsets.UTF_8);
    private static final String INFO = "diurnal-notes-dek";

    @Test
    void deriveKey_isDeterministic() {
        assertThat(Hkdf.deriveKey(TOKEN, INFO))
            .as("the same input and purpose must always derive the same key, or a stored wrapped key could never be reopened")
            .isEqualTo(Hkdf.deriveKey(TOKEN, INFO));
    }

    @Test
    void deriveKey_returnsAnAesKeyLengthValue() {
        assertThat(Hkdf.deriveKey(TOKEN, INFO))
            .as("the derived key should be exactly AES-256's key size")
            .hasSize(Aes256Gcm.KEY_BYTES);
    }

    @Test
    void deriveKey_separatesPurposes() {
        assertThat(Hkdf.deriveKey(TOKEN, INFO))
            .as("the same input under a different purpose label should derive an unrelated key")
            .isNotEqualTo(Hkdf.deriveKey(TOKEN, "diurnal-something-else"));
    }

    @Test
    void deriveKey_separatesInputs() {
        final byte[] otherToken = "b-32-byte-session-token-value!!!".getBytes(StandardCharsets.UTF_8);

        assertThat(Hkdf.deriveKey(TOKEN, INFO))
            .as("a different session token should derive a different key")
            .isNotEqualTo(Hkdf.deriveKey(otherToken, INFO));
    }

    @Test
    void deriveKey_isSensitiveToSingleBitOfTheInput() {
        final byte[] nearlyTheSame = TOKEN.clone();
        nearlyTheSame[0] ^= 0x01;

        assertThat(Hkdf.deriveKey(TOKEN, INFO))
            .as("flipping one bit of the input should change the derived key entirely")
            .isNotEqualTo(Hkdf.deriveKey(nearlyTheSame, INFO));
    }

    @Test
    void deriveKey_separatesPurposesOfTheSameLength() {
        // Deliberately the same length as each other: two labels of DIFFERENT lengths would derive different keys even if
        // the label's bytes were never mixed in at all, so only an equal-length pair proves the content is what separates them.
        assertThat(Hkdf.deriveKey(TOKEN, "diurnal-purpose-a"))
            .as("two purpose labels of the same length but different content should derive unrelated keys")
            .isNotEqualTo(Hkdf.deriveKey(TOKEN, "diurnal-purpose-b"));
    }

    @Test
    void deriveKey_acceptsAnEmptyPurposeLabel() {
        assertThat(Hkdf.deriveKey(TOKEN, ""))
            .as("an empty purpose label is still a valid derivation, and should differ from a labelled one")
            .hasSize(Aes256Gcm.KEY_BYTES)
            .isNotEqualTo(Hkdf.deriveKey(TOKEN, INFO));
    }
}
