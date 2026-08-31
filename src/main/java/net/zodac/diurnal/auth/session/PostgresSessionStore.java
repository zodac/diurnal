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

import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Database-backed {@link SessionStore}: sessions live in the {@code sessions} table, keyed by the SHA-256 hash of the raw token. Suits the
 * single-instance deployment; durable across restarts.
 *
 * <p>
 * {@link #resolve(String, Instant)} is deliberately NOT {@code @Transactional}, and that is load-bearing for every authenticated request in the app.
 * A {@code @Transactional} read runs in a transaction-scoped persistence context that closes at commit, so the {@link User} its {@code JOIN FETCH}
 * loaded is discarded the moment the method returns - and the resource's first {@code CurrentUser.get()} then reads that very same row a second time.
 * Reading without a transaction instead uses the REQUEST-scoped persistence context, which outlives authentication, so the user is still managed when
 * the resource asks for it and {@code CurrentUser} answers from the first-level cache with no statement at all. Measured against a real database:
 * {@code GET /api/v1/users/me} and {@code GET /settings} went from two statements to one, and {@code GET /} from four to three. A
 * {@code @Transactional} endpoint (a write) opens its own persistence context as it always did, so it is unaffected either way.
 *
 * <p>
 * The two writes {@code resolve} may need therefore open short transactions of their own, programmatically rather than by annotation - a
 * {@code @Transactional} method on this bean would be a self-call and go unintercepted, and the alternative (the {@code self} CDI-proxy pattern
 * {@code AuthenticationService} uses) buys nothing here, where each write is a single statement rather than a read-modify-write. Both are bulk
 * statements keyed on the token hash, so neither has to re-read the row it is about to touch, and both JOIN an enclosing transaction where there is
 * one.
 */
@ApplicationScoped
public class PostgresSessionStore implements SessionStore {

    private static final Logger LOGGER = LogManager.getLogger(PostgresSessionStore.class);

    // Coalesce the per-request last-used "touch": skip the UPDATE unless the stored value is at least
    // this stale. With an idle timeout of days, a minute of slack in the sliding window is immaterial,
    // and this turns "a write on every authenticated request" into "at most one write per minute".
    private static final Duration LAST_USED_BUMP_INTERVAL = Duration.ofMinutes(1L);

    private final SessionConfig sessionConfig;

    /**
     * Injects the session settings.
     *
     * @param sessionConfig the session settings (timeouts, cookie policy)
     */
    @Inject
    public PostgresSessionStore(final SessionConfig sessionConfig) {
        this.sessionConfig = sessionConfig;
    }

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
    public Optional<User> resolve(final String rawToken, final Instant now) {
        final byte[] tokenHash = SessionTokens.hash(rawToken);
        final Optional<Session> found = Session.findByTokenHash(tokenHash);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        final Session session = found.get();
        if (!SessionTokens.isValid(session.lastUsedAt, session.expiresAt, now, sessionConfig.idleTimeout())) {
            // The session existed but has aged out (idle or absolute timeout) - remove it so the request is challenged. This is the
            // "why was I suddenly signed out?" case, uncovered by the login/logout logging in the services above it.
            LOGGER.debug("Session expired (idle/absolute timeout) - removing for user {}", session.user.email);
            QuarkusTransaction.joiningExisting().run(() -> Session.deleteByTokenHash(tokenHash));
            return Optional.empty();
        }

        if (SessionTokens.shouldBumpLastUsed(session.lastUsedAt, now, LAST_USED_BUMP_INTERVAL)) {
            QuarkusTransaction.joiningExisting().run(() -> Session.touchLastUsed(tokenHash, now));
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
