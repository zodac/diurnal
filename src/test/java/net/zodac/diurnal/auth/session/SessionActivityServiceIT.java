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

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link SessionActivityService}: resolving each user's recently-active presence from the newest {@code last_used_at} across
 * their sessions, against a real database. The pure window boundary is unit-tested in {@code SessionActivityServiceTest}.
 */
@QuarkusTest
@SuppressWarnings("NullAway.Init") // fields populated in createDbState(), called from the base @BeforeEach
class SessionActivityServiceIT extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Inject
    SessionActivityService sessionActivityService;

    @Inject
    SessionStore sessionStore;

    private User active;
    private User inactive;
    private User multiSession;
    private User noSessions;

    @Override
    protected void createDbState() {
        active = newUser("active@lt.test", "Active User");
        inactive = newUser("inactive@lt.test", "Inactive User");
        multiSession = newUser("multi@lt.test", "Multi Session User");
        noSessions = newUser("dormant@lt.test", "Dormant User");
    }

    @Test
    void recentActivityByUser_reflectsMostRecentSessionActivity() {
        // last_used_at is set to the create() instant; a session "used" 2 minutes ago is within the window.
        sessionStore.create(active, Session.AUTH_SOURCE_PASSWORD, null, null, NOW.minusSeconds(120L));
        sessionStore.create(inactive, Session.AUTH_SOURCE_PASSWORD, null, null, NOW.minusSeconds(600L));

        final Map<UUID, RecentActivity> byUser = sessionActivityService.recentActivityByUser(List.of(active.id, inactive.id, noSessions.id), NOW);

        assertThat(byUser)
                .as("A user whose newest session was used 2 minutes ago must be recently active with 120 seconds elapsed")
                .containsEntry(active.id, new RecentActivity(true, 120L));
        assertThat(byUser)
                .as("A user whose newest session was used 10 minutes ago must be inactive")
                .containsEntry(inactive.id, RecentActivity.INACTIVE);
        assertThat(byUser)
                .as("A user with no sessions must be absent from the result map")
                .doesNotContainKey(noSessions.id);
    }

    @Test
    void recentActivityByUser_usesNewestSessionAcrossDevices() {
        // Two sessions for one user: a stale one and a fresh one. Presence must key off the NEWER last_used_at.
        sessionStore.create(multiSession, Session.AUTH_SOURCE_PASSWORD, null, null, NOW.minusSeconds(1800L));
        sessionStore.create(multiSession, Session.AUTH_SOURCE_PASSWORD, null, null, NOW.minusSeconds(60L));

        final Map<UUID, RecentActivity> byUser = sessionActivityService.recentActivityByUser(List.of(multiSession.id), NOW);

        assertThat(byUser)
                .as("Presence must come from the newest session across a user's devices (used 60 seconds ago)")
                .containsEntry(multiSession.id, new RecentActivity(true, 60L));
    }

    @Test
    void recentActivityForUser_withNoSessions_isInactive() {
        assertThat(sessionActivityService.recentActivityForUser(noSessions.id, NOW))
                .as("A user with no sessions must resolve to INACTIVE")
                .isEqualTo(RecentActivity.INACTIVE);
    }

    @Test
    void recentActivityForUser_withRecentSession_isActive() {
        sessionStore.create(active, Session.AUTH_SOURCE_PASSWORD, null, null, NOW.minusSeconds(30L));

        assertThat(sessionActivityService.recentActivityForUser(active.id, NOW))
                .as("A user whose session was used 30 seconds ago must be recently active")
                .isEqualTo(new RecentActivity(true, 30L));
    }
}
