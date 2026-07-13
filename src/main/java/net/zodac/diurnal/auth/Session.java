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

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import net.zodac.diurnal.user.User;
import org.jspecify.annotations.Nullable;

/**
 * A server-side authenticated session — the revocable counterpart to the opaque token held by a client (as the {@code diurnal_session} cookie for the
 * web UI, or a Bearer token for the REST API).
 *
 * <p>
 * Only the SHA-256 {@link #tokenHash} of the raw token is persisted, so a read-only leak of this table hands out no usable sessions. Roles are
 * deliberately not stored here — they are resolved live from the linked {@link User} on each request — so an admin/role change takes effect without
 * touching sessions. Revoking a session is simply deleting its row.
 */
@Entity
@Table(name = "sessions")
public class Session extends PanacheEntityBase {

    /**
     * {@code auth_source} value for a session created by password (form or API) login.
     */
    public static final String AUTH_SOURCE_PASSWORD = "password";

    /**
     * {@code auth_source} value for a session created after a successful OIDC login.
     */
    public static final String AUTH_SOURCE_OIDC = "oidc";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    // SHA-256 of the raw opaque token the client presents; the raw token itself is never stored.
    @Column(name = "token_hash", nullable = false, unique = true)
    public byte[] tokenHash;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    // How this session was established: AUTH_SOURCE_PASSWORD or AUTH_SOURCE_OIDC.
    @Column(name = "auth_source", nullable = false)
    public String authSource;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt = Instant.now();

    // Bumped to "now" on every authenticated request; (this + idle timeout) is the sliding expiry.
    @Column(name = "last_used_at", nullable = false)
    public Instant lastUsedAt = Instant.now();

    // Absolute cap, set at creation to createdAt + absolute lifetime; never extended.
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Column(name = "user_agent")
    public @Nullable String userAgent;

    @Column(name = "client_ip")
    public @Nullable String clientIp;

    /**
     * Finds the session whose stored hash matches the given token hash.
     */
    public static Optional<Session> findByTokenHash(final byte[] tokenHash) {
        return find("tokenHash", tokenHash).firstResultOptional();
    }

    /**
     * Deletes the session identified by the given token hash, returning the number of rows removed.
     */
    public static void deleteByTokenHash(final byte[] tokenHash) {
        delete("tokenHash", tokenHash);
    }

    /**
     * Deletes every session belonging to the given user, returning the number of rows removed.
     */
    public static void deleteByUser(final UUID userId) {
        delete("user.id", userId);
    }

    /**
     * Deletes every session belonging to the given user except the one identified by {@code keepTokenHash}, returning the number of rows removed.
     */
    public static void deleteByUserExcept(final UUID userId, final byte[] keepTokenHash) {
        delete("user.id = ?1 and tokenHash <> ?2", userId, keepTokenHash);
    }

    /**
     * Deletes every session whose absolute expiry is at or before the given instant, returning the number of rows removed. Idle-expired sessions are
     * pruned lazily on access; this sweeps the ones that are never presented again.
     */
    public static long deleteExpired(final Instant now) {
        return delete("expiresAt <= ?1", now);
    }
}
