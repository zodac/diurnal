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

package net.zodac.diurnal.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link User#authSource()}: the sign-in-source derivation from which credentials the account holds.
 */
class UserAuthSourceTest {

    @Test
    void authSource_passwordOnly_isLocal() {
        assertThat(user("hash", null).authSource())
            .as("unexpected value")
            .isEqualTo("local");
    }

    @Test
    void authSource_oidcOnly_isOidc() {
        assertThat(user(null, "subject").authSource())
            .as("unexpected value")
            .isEqualTo("oidc");
    }

    @Test
    void authSource_hybrid_isBoth() {
        assertThat(user("hash", "subject").authSource())
            .as("unexpected value")
            .isEqualTo("local+oidc");
    }

    @Test
    void authSource_neitherCredential_isLocal() {
        assertThat(user(null, null).authSource())
            .as("An account with neither credential (an inconsistent state) falls back to local")
            .isEqualTo("local");
    }

    @Test
    void authSource_blankCredentialsAreAbsent() {
        assertThat(user(" ", " ").authSource())
            .as("Blank stored values must not count as credentials")
            .isEqualTo("local");
    }

    private static User user(final @Nullable String passwordHash, final @Nullable String oidcSubject) {
        final User user = new User();
        user.passwordHash = passwordHash;
        user.oidcSubject = oidcSubject;
        return user;
    }
}
