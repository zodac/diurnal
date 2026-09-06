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

package net.zodac.diurnal.auth.session;

import static net.zodac.diurnal.http.HttpStatusCodes.FOUND;
import static net.zodac.diurnal.http.HttpStatusCodes.UNAUTHORIZED;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.vertx.http.runtime.security.ChallengeData;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionAuthMechanism#challengeFor(String, String)}: the REST API gets a plain {@code 401} while every browser path gets a
 * {@code 302} redirect to the sign-in page, which carries the deployment's base path.
 */
class SessionAuthMechanismTest {

    private static final String LOGIN_URL = "/login";

    @Test
    void challengeFor_apiPath_isPlainUnauthorized() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/api/v1/users/me", LOGIN_URL);
        assertThat(challenge.status)
                .as("A REST API path must get a plain 401, not a browser redirect")
                .isEqualTo(UNAUTHORIZED);
    }

    @Test
    void challengeFor_apiRoot_isPlainUnauthorized() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/api/v1/auth/logout", LOGIN_URL);
        assertThat(challenge.status)
                .as("Every /api/ path must get a 401")
                .isEqualTo(UNAUTHORIZED);
    }

    @Test
    void challengeFor_browserRoot_redirectsToLogin() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/", LOGIN_URL);
        assertThat(challenge.status)
                .as("A browser path must get a 302 redirect")
                .isEqualTo(FOUND);
    }

    @Test
    void challengeFor_nonApiPath_redirectsToLogin() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/settings", LOGIN_URL);
        assertThat(challenge.status)
                .as("A non-API browser path must redirect to login")
                .isEqualTo(FOUND);
    }

    @Test
    void challengeFor_browserPath_redirectsToTheSuppliedLoginUrl() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/settings", "/diurnal/login");
        assertThat(challenge.getHeaders())
                .as("The redirect must send the browser to the sign-in URL it was given, carrying the deployment's base path")
                .containsEntry("location", "/diurnal/login");
    }

    @Test
    void challengeFor_apiPath_carriesNoRedirectTarget() {
        final ChallengeData challenge = SessionAuthMechanism.challengeFor("/api/v1/users/me", "/diurnal/login");
        assertThat(challenge.getHeaders())
                .as("A 401 for the REST API must carry no redirect target, whatever sign-in URL was offered")
                .isEmpty();
    }
}
