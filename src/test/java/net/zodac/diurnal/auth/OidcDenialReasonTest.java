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

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OidcDenialReason}: the cookie-code round trip and the provider-name substitution in the user-facing messages.
 */
class OidcDenialReasonTest {

    @Test
    void fromCode_everyReasonRoundTrips() {
        for (final OidcDenialReason reason : OidcDenialReason.values()) {
            assertThat(OidcDenialReason.fromCode(reason.code()))
                .as("Every reason's code must resolve back to the reason itself")
                .contains(reason);
        }
    }

    @Test
    void fromCode_unknownCode_isEmpty() {
        assertThat(OidcDenialReason.fromCode("no-such-code"))
            .as("An unrecognised cookie value must not resolve to a reason")
            .isEmpty();
    }

    @Test
    void fromCode_null_isEmpty() {
        assertThat(OidcDenialReason.fromCode(null))
            .as("An absent cookie must not resolve to a reason")
            .isEmpty();
    }

    @Test
    void message_accountExists_substitutesProviderName() {
        assertThat(OidcDenialReason.ACCOUNT_EXISTS.message("Authelia"))
            .as("The account-exists message must name the configured provider")
            .contains("connect Authelia from the Settings page");
    }

    @Test
    void message_withoutPlaceholder_isReturnedVerbatim() {
        assertThat(OidcDenialReason.NOT_IN_GROUP.message("Authelia"))
            .as("Messages without a provider placeholder are unchanged")
            .isEqualTo("You are not authorised to access this service. Please contact the application owner.");
    }

    @Test
    void message_everyReason_promptsOrInstructsTheUser() {
        for (final OidcDenialReason reason : OidcDenialReason.values()) {
            assertThat(reason.message("Authelia"))
                .as("Every denial message must be non-blank and end as a sentence")
                .isNotBlank()
                .endsWith(".");
        }
    }
}
