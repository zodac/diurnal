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

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SessionActivityService}'s pure branching: the {@link SessionActivityService#assess} window boundary (with its null and
 * clock-skew guards) and the empty-input short-circuit of {@link SessionActivityService#recentActivityByUser}. The database-backed query paths are
 * covered by {@code SessionActivityServiceIT}.
 */
class SessionActivityServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    void assess_nullLastSeen_isInactive() {
        assertThat(SessionActivityService.assess(null, NOW))
                .as("A user with no sessions must be inactive")
                .isEqualTo(RecentActivity.INACTIVE);
    }

    @Test
    void assess_justNow_isActiveWithZeroSeconds() {
        assertThat(SessionActivityService.assess(NOW, NOW))
                .as("A request at the current instant must be active with zero seconds elapsed")
                .isEqualTo(new RecentActivity(true, 0L));
    }

    @Test
    void assess_wellWithinWindow_isActiveWithElapsedSeconds() {
        assertThat(SessionActivityService.assess(NOW.minusSeconds(60L), NOW))
                .as("A request 60 seconds ago must be active and report 60 seconds")
                .isEqualTo(new RecentActivity(true, 60L));
    }

    @Test
    void assess_justInsideWindow_isActive() {
        assertThat(SessionActivityService.assess(NOW.minusSeconds(299L), NOW))
                .as("A request 299 seconds ago (just inside the 5-minute window) must be active")
                .isEqualTo(new RecentActivity(true, 299L));
    }

    @Test
    void assess_exactlyAtWindowBoundary_isInactive() {
        assertThat(SessionActivityService.assess(NOW.minusSeconds(300L), NOW))
                .as("A request exactly 5 minutes ago must be inactive (the boundary counts as expired)")
                .isEqualTo(RecentActivity.INACTIVE);
    }

    @Test
    void assess_pastWindow_isInactive() {
        assertThat(SessionActivityService.assess(NOW.minusSeconds(600L), NOW))
                .as("A request 10 minutes ago must be inactive")
                .isEqualTo(RecentActivity.INACTIVE);
    }

    @Test
    void assess_lastSeenInFuture_clampsToZeroSeconds() {
        assertThat(SessionActivityService.assess(NOW.plusSeconds(10L), NOW))
                .as("A future last-seen (clock skew) must clamp to zero seconds rather than report a negative age")
                .isEqualTo(new RecentActivity(true, 0L));
    }

    @Test
    void recentActivityByUser_emptyInput_isEmptyMap() {
        assertThat(new SessionActivityService().recentActivityByUser(List.of(), NOW))
                .as("No user ids must short-circuit to an empty map without touching the database")
                .isEmpty();
    }
}
