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

import java.util.Optional;
import net.zodac.diurnal.auth.OpenApiDocsAccess.Outcome;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OpenApiDocsAccess#decide(Optional)}: the documentation surface is served only
 * to an administrator, redirected to login when anonymous, and forbidden for a non-administrator.
 */
class OpenApiDocsAccessTest {

    @Test
    void decide_anonymous_redirectsToLogin() {
        final Outcome outcome = OpenApiDocsAccess.decide(Optional.empty());
        assertThat(outcome)
                .as("An anonymous request must be redirected to login")
                .isEqualTo(Outcome.REDIRECT_TO_LOGIN);
    }

    @Test
    void decide_administrator_isAllowed() {
        final Outcome outcome = OpenApiDocsAccess.decide(Optional.of(userWithRole(User.ROLE_ADMIN)));
        assertThat(outcome)
                .as("An authenticated administrator must be allowed to see the documentation")
                .isEqualTo(Outcome.ALLOW);
    }

    @Test
    void decide_nonAdministrator_isForbidden() {
        final Outcome outcome = OpenApiDocsAccess.decide(Optional.of(userWithRole(User.ROLE_USER)));
        assertThat(outcome)
                .as("An authenticated non-administrator must be forbidden")
                .isEqualTo(Outcome.FORBIDDEN);
    }

    private static User userWithRole(final String role) {
        final User user = new User();
        user.role = role;
        return user;
    }
}
