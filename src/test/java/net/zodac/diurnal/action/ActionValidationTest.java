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

package net.zodac.diurnal.action;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActionValidation}: the colour validation shared by the web UI and the public API. A malformed colour is rejected (never
 * silently corrected); only an absent colour on creation falls back to the default.
 */
class ActionValidationTest {

    @Test
    void isColourInvalid_validLowercaseHex_isValid() {
        assertThat(ActionValidation.isColourInvalid("#ff5500"))
            .as("a valid lowercase hex colour should be accepted")
            .isFalse();
    }

    @Test
    void isColourInvalid_validUppercaseHex_isValid() {
        assertThat(ActionValidation.isColourInvalid("#AABB09"))
            .as("a valid uppercase hex colour should be accepted")
            .isFalse();
    }

    @Test
    void isColourInvalid_missingHash_isInvalid() {
        assertThat(ActionValidation.isColourInvalid("ff5500"))
            .as("a colour without the leading # should be rejected")
            .isTrue();
    }

    @Test
    void isColourInvalid_shortHex_isInvalid() {
        assertThat(ActionValidation.isColourInvalid("#fff"))
            .as("a 3-digit hex colour should be rejected")
            .isTrue();
    }

    @Test
    void isColourInvalid_nonHexCharacters_isInvalid() {
        assertThat(ActionValidation.isColourInvalid("#gggggg"))
            .as("non-hex characters should be rejected")
            .isTrue();
    }
}
