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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BrowserLoginChallengeMechanismTest {

    // ── REST API paths → a plain 401 (a /login redirect is wrong for a programmatic client) ──────

    @ParameterizedTest
    @ValueSource(strings = {"/api/users/me", "/api/auth/login", "/api/"})
    void challengeFor_apiPath_returnsPlain401(final String path) {
        final ChallengeData challenge = BrowserLoginChallengeMechanism.challengeFor(path);
        assertThat(challenge.status)
            .as("API paths should be challenged with a 401")
            .isEqualTo(401);
        assertThat(challenge.getHeaders())
            .as("a plain 401 should set no challenge headers (no /login redirect)")
            .isEmpty();
    }

    // ── Everything else (incl. the boundary /api and /apixyz) → 302 redirect to /login ───────────

    @ParameterizedTest
    @ValueSource(strings = {"/", "/settings", "/logs/events", "/api", "/apixyz"})
    void challengeFor_nonApiPath_redirectsToLogin(final String path) {
        final ChallengeData challenge = BrowserLoginChallengeMechanism.challengeFor(path);
        assertThat(challenge.status)
            .as("browser paths should be redirected")
            .isEqualTo(302);
        assertThat(challenge.getHeaders().get("location"))
            .as("a redirect should set the Location header to the login page")
            .isEqualTo("/login");
    }

    @Test
    void challengeFor_apiPrefixIsCaseSensitiveAndExact() {
        assertThat(BrowserLoginChallengeMechanism.challengeFor("/API/users/me").status)
            .as("the /api/ prefix match is case-sensitive, so an upper-case path is a browser redirect")
            .isEqualTo(302);
    }
}
