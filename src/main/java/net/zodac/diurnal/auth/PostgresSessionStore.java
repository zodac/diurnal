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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.zodac.diurnal.config.SessionConfig;
import net.zodac.diurnal.user.User;
import org.jspecify.annotations.Nullable;

/**
 * Database-backed {@link SessionStore}: sessions live in the {@code sessions} table, keyed by the SHA-256 hash of the raw token. Suits the
 * single-instance deployment; durable across restarts.
 */
@ApplicationScoped
public class PostgresSessionStore implements SessionStore {

    // Coalesce the per-request last-used "touch": skip the UPDATE unless the stored value is at least
    // this stale. With an idle timeout of days, a minute of slack in the sliding window is immaterial,
    // and this turns "a write on every authenticated request" into "at most one write per minute".
    private static final Duration LAST_USED_BUMP_INTERVAL = Duration.ofMinutes(1L);

    @Inject
    SessionConfig sessionConfig;

    @Override
    @Transactional
    public String create(
        final User user,
        final String authSource,
        final @Nullable String userAgent,
        final @Nullable String clientIp,
        final Instant now) {
        final String rawToken = SessionTokens.generate();
        final Session session = new Session();
        session.tokenHash = SessionTokens.hash(rawToken);
        session.user = user;
        session.authSource = authSource;
        session.createdAt = now;
        session.lastUsedAt = now;
        session.expiresAt = now.plus(sessionConfig.absoluteTimeout());
        session.userAgent = userAgent;
        session.clientIp = clientIp;
        session.persist();
        return rawToken;
    }

    @Override
    @Transactional
    public Optional<User> resolve(final String rawToken, final Instant now) {
        final Optional<Session> found = Session.findByTokenHash(SessionTokens.hash(rawToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        final Session session = found.get();
        if (!SessionTokens.isValid(session.lastUsedAt, session.expiresAt, now, sessionConfig.idleTimeout())) {
            session.delete();
            return Optional.empty();
        }

        if (SessionTokens.shouldBumpLastUsed(session.lastUsedAt, now, LAST_USED_BUMP_INTERVAL)) {
            session.lastUsedAt = now;
            session.persist();
        }
        return Optional.of(session.user);
    }

    @Override
    @Transactional
    public void revoke(final String rawToken) {
        Session.deleteByTokenHash(SessionTokens.hash(rawToken));
    }

    @Override
    @Transactional
    public void revokeAllForUser(final UUID userId) {
        Session.deleteByUser(userId);
    }

    @Override
    @Transactional
    public void revokeOthersForUser(final UUID userId, final String currentRawToken) {
        Session.deleteByUserExcept(userId, SessionTokens.hash(currentRawToken));
    }
}
