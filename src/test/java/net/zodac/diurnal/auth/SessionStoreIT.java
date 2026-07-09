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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link PostgresSessionStore}: token resolution, idle expiry and the three
 * revocation modes (single, all, all-but-current) against a real database.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class SessionStoreIT extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Inject
    SessionStore sessionStore;

    User user;

    @Override
    protected void createDbState() {
        user = newUser("sessions@lt.test", "Session User");
    }

    @Test
    void create_thenResolve_returnsOwningUser() {
        final String token = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, "agent", "test-client", NOW);

        final Optional<User> resolved = sessionStore.resolve(token, NOW);
        assertThat(resolved)
            .as("A freshly created session must resolve to its owning user")
            .isPresent();
        assertThat(resolved.orElseThrow().id)
            .as("Resolved user must be the session owner")
            .isEqualTo(user.id);
    }

    @Test
    void resolve_unknownToken_isEmpty() {
        assertThat(sessionStore.resolve("not-a-real-token", NOW))
            .as("An unknown token must not resolve")
            .isEmpty();
    }

    @Test
    void resolve_pastIdleTimeout_isEmptyAndPrunesRow() {
        final String token = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, NOW);

        // Default idle timeout is 30 days; resolve 31 days later.
        final Instant later = NOW.plus(Duration.ofDays(31L));
        assertThat(sessionStore.resolve(token, later))
            .as("A session past its idle timeout must not resolve")
            .isEmpty();
        // The expired row is pruned on encounter, so it does not resolve even if the clock rewinds.
        assertThat(sessionStore.resolve(token, NOW))
            .as("The idle-expired session row must have been deleted")
            .isEmpty();
    }

    @Test
    void revoke_thenResolve_isEmpty() {
        final String token = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, NOW);
        sessionStore.revoke(token);
        assertThat(sessionStore.resolve(token, NOW))
            .as("A revoked session must not resolve")
            .isEmpty();
    }

    @Test
    void revokeAllForUser_removesEverySession() {
        final String tokenA = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, NOW);
        final String tokenB = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, NOW);

        sessionStore.revokeAllForUser(user.id);

        assertThat(sessionStore.resolve(tokenA, NOW))
            .as("Log-out-everywhere must revoke the first device")
            .isEmpty();
        assertThat(sessionStore.resolve(tokenB, NOW))
            .as("Log-out-everywhere must revoke the second device")
            .isEmpty();
    }

    @Test
    void revokeOthersForUser_keepsCurrentAndRemovesRest() {
        final String current = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, NOW);
        final String other = sessionStore.create(user, Session.AUTH_SOURCE_PASSWORD, null, null, NOW);

        sessionStore.revokeOthersForUser(user.id, current);

        assertThat(sessionStore.resolve(current, NOW))
            .as("The session that changed the password must stay valid")
            .isPresent();
        assertThat(sessionStore.resolve(other, NOW))
            .as("Every other session must be revoked on password change")
            .isEmpty();
    }
}
