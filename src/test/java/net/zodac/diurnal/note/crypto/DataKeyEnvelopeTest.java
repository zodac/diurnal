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

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DataKeyEnvelope}: that a data key survives the wrapping intact, that the wrong master key does not open one, and that a
 * wrapping lifted from one account cannot be opened against another.
 */
class DataKeyEnvelopeTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_OWNER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void wrapThenUnwrap_returnsTheDataKey() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final byte[] masterKey = Aes256Gcm.randomKey();

        final byte[] wrapped = DataKeyEnvelope.wrap(dataKey, masterKey, OWNER);

        assertThat(DataKeyEnvelope.unwrap(wrapped, masterKey, OWNER))
            .as("the master key that wrapped a data key must open it again, or every note sealed under it is lost")
            .contains(dataKey);
    }

    @Test
    void wrap_doesNotStoreTheDataKeyInTheClear() {
        final byte[] dataKey = Aes256Gcm.randomKey();

        final byte[] wrapped = DataKeyEnvelope.wrap(dataKey, Aes256Gcm.randomKey(), OWNER);

        assertThat(wrapped)
            .as("the wrapped form must differ from the key it wraps")
            .isNotEqualTo(dataKey);
    }

    @Test
    void wrap_derivesFromTheMasterRatherThanUsingItDirectly() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final byte[] masterKey = Aes256Gcm.randomKey();

        final byte[] wrapped = DataKeyEnvelope.wrap(dataKey, masterKey, OWNER);

        assertThat(Aes256Gcm.open(masterKey, wrapped, OWNER.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .as("the configured master must not be the wrapping key itself - it is HKDF-derived, so other uses cannot share key material")
            .isEmpty();
    }

    @Test
    void unwrap_refusesDifferentMasterKey() {
        final byte[] wrapped = DataKeyEnvelope.wrap(Aes256Gcm.randomKey(), Aes256Gcm.randomKey(), OWNER);

        assertThat(DataKeyEnvelope.unwrap(wrapped, Aes256Gcm.randomKey(), OWNER))
            .as("a changed or mistyped NOTE_ENCRYPTION_KEY must not open the data key")
            .isEmpty();
    }

    @Test
    void unwrap_refusesWrappingBelongingToAnotherAccount() {
        final byte[] masterKey = Aes256Gcm.randomKey();
        final byte[] wrapped = DataKeyEnvelope.wrap(Aes256Gcm.randomKey(), masterKey, OWNER);

        assertThat(DataKeyEnvelope.unwrap(wrapped, masterKey, OTHER_OWNER))
            .as("the owner is bound in as associated data, so a wrapping moved to another account's row must not open")
            .isEmpty();
    }

    @Test
    void wrap_usesFreshIvEachTime() {
        final byte[] dataKey = Aes256Gcm.randomKey();
        final byte[] masterKey = Aes256Gcm.randomKey();

        assertThat(DataKeyEnvelope.wrap(dataKey, masterKey, OWNER))
            .as("wrapping the same key twice should produce different blobs")
            .isNotEqualTo(DataKeyEnvelope.wrap(dataKey, masterKey, OWNER));
    }
}
