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

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Covers the "account deleted between the credential check and the follow-up write" edge case. Both {@code AuthenticationService} and
 * {@code PasswordChangeService} verify credentials outside a transaction (so the expensive Argon2id work never holds a database connection), then
 * re-read the account by ID inside the short write transaction; if the account has since vanished, the write is skipped and the neutral failure
 * result is returned rather than throwing.
 */
@QuarkusTest
class AccountVanishedMidRequestIT extends IntegrationTestBase {

    @Inject
    AuthenticationService authenticationService;

    @Inject
    PasswordChangeService passwordChangeService;

    @Test
    void recordLogin_accountNoLongerExists_returnsInvalidCredentials() {
        final LoginResult result = authenticationService.recordLogin(UUID.randomUUID(), null);

        assertThat(result)
            .as("a verified login whose account vanished before the write must resolve to invalid credentials, not a persisted login")
            .isInstanceOf(LoginResult.InvalidCredentials.class);
    }

    @Test
    void applyChange_accountNoLongerExists_returnsNotLocalAccount() {
        final PasswordChangeResult result = passwordChangeService.applyChange(UUID.randomUUID(), "irrelevant-hash", null);

        assertThat(result)
            .as("a password change whose account vanished before the write must report a non-local account, not persist a hash")
            .isInstanceOf(PasswordChangeResult.NotLocalAccount.class);
    }
}
