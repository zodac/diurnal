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

import io.quarkus.vertx.http.runtime.security.ChallengeData;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionAuthMechanism#challengeFor(String)}: the REST API gets a plain
 * {@code 401} while every browser path gets a {@code 302} redirect to {@code /login}.
 */
class SessionAuthMechanismTest {

    @Test
    void challengeFor_apiPath_isPlainUnauthorized() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/api/users/me");
        assertThat(challenge.status)
                .as("A REST API path must get a plain 401, not a browser redirect")
                .isEqualTo(401);
    }

    @Test
    void challengeFor_apiRoot_isPlainUnauthorized() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/api/auth/logout");
        assertThat(challenge.status)
                .as("Every /api/ path must get a 401")
                .isEqualTo(401);
    }

    @Test
    void challengeFor_browserRoot_redirectsToLogin() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/");
        assertThat(challenge.status)
                .as("A browser path must get a 302 redirect")
                .isEqualTo(302);
    }

    @Test
    void challengeFor_nonApiPath_redirectsToLogin() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/settings");
        assertThat(challenge.status)
                .as("A non-API browser path must redirect to login")
                .isEqualTo(302);
    }
}
