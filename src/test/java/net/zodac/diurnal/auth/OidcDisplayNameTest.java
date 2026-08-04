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

package net.zodac.diurnal.auth;

import static org.assertj.core.api.Assertions.assertThat;

import net.zodac.diurnal.text.TextFields;
import org.junit.jupiter.api.Test;

class OidcDisplayNameTest {

    private static final String EMAIL = "ada@diurnal.example.com";

    @Test
    void from_usableClaim_isTaken() {
        assertThat(OidcDisplayName.from("Ada Lovelace", EMAIL))
            .as("unexpected value")
            .isEqualTo("Ada Lovelace");
    }

    @Test
    void from_absentClaim_fallsBackToTheEmailLocalPart() {
        assertThat(OidcDisplayName.from(null, EMAIL))
            .as("unexpected value")
            .isEqualTo("ada");
    }

    @Test
    void from_blankClaim_fallsBackToTheEmailLocalPart() {
        assertThat(OidcDisplayName.from("   ", EMAIL))
            .as("unexpected value")
            .isEqualTo("ada");
    }

    @Test
    void from_claimRepeatingTheEmail_fallsBackToTheEmailLocalPart() {
        // Some IdPs fill the name claim with the email; a full address is never a useful display name.
        assertThat(OidcDisplayName.from("ADA@DIURNAL.EXAMPLE.COM", EMAIL))
            .as("unexpected value")
            .isEqualTo("ada");
    }

    @Test
    void from_claimIsCleanedLikeAnyOtherDisplayName() {
        assertThat(OidcDisplayName.from("  Ada   Lovelace  ", EMAIL))
            .as("an IdP must not be able to seed a name the Settings page would refuse")
            .isEqualTo("Ada Lovelace");
    }

    @Test
    void from_overLongClaim_isTruncatedRatherThanRefused() {
        // Coerced, not rejected: there is nobody to report an error to mid sign-in.
        final String claim = "a".repeat(TextFields.DISPLAY_NAME_MAX_LENGTH + 50);

        assertThat(OidcDisplayName.from(claim, EMAIL))
            .as("unexpected value")
            .isEqualTo("a".repeat(TextFields.DISPLAY_NAME_MAX_LENGTH));
    }

    @Test
    void from_unusableClaimAndLocalPart_fallsBackToTheWholeEmail() {
        // A one-character local part is under the display-name minimum, so the address itself stands in.
        assertThat(OidcDisplayName.from(null, "a@diurnal.example.com"))
            .as("unexpected value")
            .isEqualTo("a@diurnal.example.com");
    }

    @Test
    void from_emailWithNoLocalPartToTake_isUsedWhole() {
        assertThat(OidcDisplayName.from(null, "ada"))
            .as("an email with no @ has no local part to fall back to")
            .isEqualTo("ada");
    }

    @Test
    void from_nothingUsableAtAll_returnsTheEmail() {
        // Every candidate is under the minimum; provisioning must still yield a name.
        assertThat(OidcDisplayName.from(null, "a"))
            .as("provisioning must never fail for want of a display name")
            .isEqualTo("a");
    }
}
