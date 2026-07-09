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

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.zodac.diurnal.user.User;
import org.jspecify.annotations.Nullable;

/**
 * Persistence boundary for server-side sessions. Callers deal only in raw opaque tokens; the
 * implementation is responsible for hashing them for storage and lookup.
 *
 * <p>
 * The default {@link PostgresSessionStore} keeps sessions in the database, which suits the
 * single-instance deployment. The interface exists so a shared-cache implementation (e.g. Redis)
 * could be substituted if the deployment ever scales horizontally, without touching callers.
 */
public interface SessionStore {

    /**
     * Creates a new session for the given user and returns the freshly minted raw token to hand to the
     * client. Only the token's hash is persisted; this is the one moment the raw token exists.
     *
     * @param user       the authenticated user
     * @param authSource {@link Session#AUTH_SOURCE_PASSWORD} or {@link Session#AUTH_SOURCE_OIDC}
     * @param userAgent  the requesting client's user agent, or {@code null} if unknown
     * @param clientIp   the requesting client's IP, or {@code null} if unknown
     * @param now        the current instant (from {@code AppClock})
     * @return the raw opaque token to deliver to the client
     */
    String create(User user, String authSource, @Nullable String userAgent, @Nullable String clientIp, Instant now);

    /**
     * Resolves a raw token to its owning user if the session exists and is still valid, refreshing the
     * session's last-used time as a side effect. Expired sessions are pruned on encounter.
     *
     * @param rawToken the raw token presented by the client
     * @param now      the current instant (from {@code AppClock})
     * @return the owning {@link User}, or empty if the token is unknown or expired
     */
    Optional<User> resolve(String rawToken, Instant now);

    /**
     * Revokes (deletes) the single session identified by the given raw token, if present.
     *
     * @param rawToken the raw token whose session should be revoked
     */
    void revoke(String rawToken);

    /**
     * Revokes every session belonging to the given user — the "log out from everywhere" action,
     * including the caller's own current session.
     *
     * @param userId the user whose sessions should all be revoked
     */
    void revokeAllForUser(UUID userId);

    /**
     * Revokes every session belonging to the given user except the one identified by the current raw
     * token — used on password change to evict all other devices while keeping the caller signed in.
     *
     * @param userId          the user whose other sessions should be revoked
     * @param currentRawToken the raw token of the session to keep
     */
    void revokeOthersForUser(UUID userId, String currentRawToken);
}
