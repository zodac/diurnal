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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link MasterKey}: every way a configured key can be unusable, and the one way it can be usable.
 *
 * <p>
 * {@link MasterKey#decode(String)} runs on the path that opens a user's data key, so a fault here is the difference between an application that
 * refuses to start and one that silently cannot read anybody's notes.
 */
class MasterKeyTest {

    private static final String VALID = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(
        java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void validate_acceptsKeyOfExactlyTheRightLength() {
        assertThat(MasterKey.validate(VALID))
            .as("32 bytes of valid base64 is the one usable shape")
            .isEmpty();
    }

    @Test
    void validate_toleratesSurroundingWhitespace() {
        assertThat(MasterKey.validate("  " + VALID + "\n"))
            .as("a key pasted from `openssl rand` carries a trailing newline, which must not make it unusable")
            .isEmpty();
    }

    @Test
    void validate_rejectsAbsentKey() {
        assertThat(MasterKey.validate(null))
            .as("an unset key must be reported, naming the variable and how to generate one")
            .hasValueSatisfying(problem -> assertThat(problem).contains("NOTE_ENCRYPTION_KEY", "openssl rand -base64 32"));
    }

    @Test
    void validate_rejectsBlankKey() {
        assertThat(MasterKey.validate("   "))
            .as("a variable set to whitespace is as unset as one never set at all")
            .isPresent();
    }

    @Test
    void validate_rejectsMalformedBase64() {
        assertThat(MasterKey.validate("not base64 !!!"))
            .as("a mistyped key must be reported as malformed rather than thrown from deep in a request")
            .hasValueSatisfying(problem -> assertThat(problem).contains("base64"));
    }

    @Test
    void validate_rejectsKeyOfTheWrongLength() {
        final String tooShort = Base64.getEncoder().encodeToString("short".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(MasterKey.validate(tooShort))
            .as("a key of the wrong length must name both the expected and the actual size")
            .hasValueSatisfying(problem -> assertThat(problem).contains("32 bytes", "5"));
    }

    @Test
    void decode_returnsTheKeyBytes() {
        assertThat(MasterKey.decode(VALID))
            .as("a usable key must decode to exactly the bytes it encodes")
            .isEqualTo("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .hasSize(Aes256Gcm.KEY_BYTES);
    }

    @Test
    void decode_stripsSurroundingWhitespace() {
        assertThat(MasterKey.decode(VALID + "\n"))
            .as("decode must accept exactly what validate accepts, or a key could pass startup and fail at first use")
            .isEqualTo(MasterKey.decode(VALID));
    }

    @Test
    void decode_throwsOnEveryUnusableKey() {
        assertThatThrownBy(() -> MasterKey.decode(""))
            .as("decoding an absent key is a programming or deployment error, not a value to carry on with")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NOTE_ENCRYPTION_KEY");
        assertThatThrownBy(() -> MasterKey.decode("not base64 !!!"))
            .as("decoding a malformed key must throw rather than return something unusable")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("base64");
        assertThatThrownBy(() -> MasterKey.decode(Base64.getEncoder().encodeToString(new byte[8])))
            .as("decoding a short key must throw rather than hand a weak key to the cipher")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("32 bytes");
    }
}
