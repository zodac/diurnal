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

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Computes each user's "recently active" presence from their sessions: a user is recently active when they made an authenticated request within
 * {@link #ACTIVE_WINDOW}. Every authenticated request already bumps the session's {@code last_used_at} (see {@code PostgresSessionStore.resolve}), so
 * presence is read straight off that column - no heartbeat, no extra request traffic. Presence is per user (a user may hold sessions on several
 * devices), so the signal is the newest {@code last_used_at} across all of the user's sessions.
 *
 * <p>
 * A read-only presentation helper: it composes the shared session data into the surface-specific {@link RecentActivity} view-model, so it needs no
 * transaction (Panache reads work without one) and no {@code *Result} type. The window boundary lives in the pure {@link #assess} method so it can be
 * unit- and mutation-tested deterministically; the two public methods are thin query glue over it, covered by an integration test.
 *
 * <p>
 * Surface policy: the "recently active" indicator is UI presence decoration (like the admin-only update-check footer arrow), not a user action or a
 * data resource, so it deliberately has no {@code /api/v1} twin.
 */
@ApplicationScoped
public class SessionActivityService {

    // How recently a user must have made an authenticated request to count as "recently active". The
    // matching client-side value (which flips a dot green -> grey once the live counter crosses it) is
    // ACTIVE_WINDOW_MS in app.js - keep the two in step.
    private static final Duration ACTIVE_WINDOW = Duration.ofMinutes(5L);

    /**
     * Resolves the recent-activity presence for each of the given users at {@code now}. Users with no sessions (never logged in, or fully logged out)
     * are absent from the returned map - callers treat a missing entry as {@link RecentActivity#INACTIVE}.
     *
     * @param userIds the users to resolve presence for (typically one page of the admin list)
     * @param now     the current instant (from {@code AppClock})
     * @return each present user's {@link RecentActivity}, keyed by user id; empty when {@code userIds} is empty
     */
    public Map<UUID, RecentActivity> recentActivityByUser(final Collection<UUID> userIds, final Instant now) {
        if (userIds.isEmpty()) {
            // Short-circuit before the query: a JPQL "IN ()" with an empty collection is invalid, and there
            // is nothing to look up anyway.
            return Map.of();
        }

        // Inline (never held in a local): Panache.getEntityManager() is a container-managed proxy.
        final List<UserLastSeen> rows = Panache.getEntityManager()
            .createQuery("""
                    SELECT new net.zodac.diurnal.auth.UserLastSeen(s.user.id, MAX(s.lastUsedAt))
                    FROM Session s
                    WHERE s.user.id IN :userIds
                    GROUP BY s.user.id""", UserLastSeen.class)
            .setParameter("userIds", userIds)
            .getResultList();

        final Map<UUID, RecentActivity> byUser = new HashMap<>();
        for (final UserLastSeen row : rows) {
            byUser.put(row.userId(), assess(row.lastSeen(), now));
        }
        return byUser;
    }

    /**
     * Resolves the recent-activity presence for a single user at {@code now} - used by the Account page for the signed-in user.
     *
     * @param userId the user to resolve presence for
     * @param now    the current instant (from {@code AppClock})
     * @return the user's {@link RecentActivity}, or {@link RecentActivity#INACTIVE} when they have no sessions
     */
    public RecentActivity recentActivityForUser(final UUID userId, final Instant now) {
        return recentActivityByUser(List.of(userId), now).getOrDefault(userId, RecentActivity.INACTIVE);
    }

    /**
     * Decides whether a user is recently active from their newest {@code last_used_at} and the current instant: active iff that request was strictly
     * within {@link #ACTIVE_WINDOW}. A {@code null} last-seen (no sessions) is inactive; a last-seen in the future (clock skew) is clamped to zero
     * seconds ago rather than reported as a negative age.
     *
     * @param lastSeen the newest {@code last_used_at} across the user's sessions, or {@code null} when they have none
     * @param now      the current instant
     * @return the resolved presence
     */
    static RecentActivity assess(final @Nullable Instant lastSeen, final Instant now) {
        if (lastSeen == null) {
            return RecentActivity.INACTIVE;
        }

        final long rawSeconds = Duration.between(lastSeen, now).getSeconds();
        final long secondsAgo = Math.max(0L, rawSeconds);
        if (secondsAgo >= ACTIVE_WINDOW.getSeconds()) {
            return RecentActivity.INACTIVE;
        }
        return new RecentActivity(true, secondsAgo);
    }
}
