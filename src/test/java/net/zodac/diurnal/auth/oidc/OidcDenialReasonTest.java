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

package net.zodac.diurnal.auth.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OidcDenialReason}: the provider-name substitution in the user-facing messages.
 */
class OidcDenialReasonTest {

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
