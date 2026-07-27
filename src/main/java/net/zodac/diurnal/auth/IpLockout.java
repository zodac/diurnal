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
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A durable audit record of a single per-IP auth lockout, written the moment the shared per-IP throttle ({@link IpThrottle}) trips for a client IP.
 * The lockout <em>enforcement</em> itself is in-memory (it resets on restart); this table is only a history log so an administrator can review recent
 * lockouts and manually clear one.
 *
 * <p>
 * A naturally-expired lockout leaves its row untouched — its {@link #lockedUntil} simply falls into the past, and the derived status becomes
 * {@code EXPIRED}. A manual unlock stamps {@link #unlockedAt}/{@link #unlockedBy}. Only rows within {@link #HISTORY_RETENTION} are ever shown, and
 * each newly-persisted lockout prunes rows older than that window, so the table stays bounded without a background sweeper.
 */
@Entity
@Table(name = "ip_lockouts")
public class IpLockout extends PanacheEntityBase {

    /**
     * How long a lockout row is retained and shown in the admin history view: one week. Rows older than this are pruned when the next lockout is
     * persisted and are never displayed.
     */
    public static final Duration HISTORY_RETENTION = Duration.ofDays(7);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    @Column(name = "ip_address", nullable = false)
    public String ipAddress;

    @Column(name = "locked_at", nullable = false, updatable = false)
    public Instant lockedAt = Instant.now();

    @Column(name = "locked_until", nullable = false)
    public Instant lockedUntil;

    @Column(name = "failure_count", nullable = false)
    public int failureCount;

    @Column(name = "unlocked_at")
    public @Nullable Instant unlockedAt;

    @Column(name = "unlocked_by")
    public @Nullable String unlockedBy;

    /**
     * Builds a lockout record for the given client IP.
     *
     * @param ipAddress    the client IP that was locked out
     * @param lockedAt     when the lockout tripped
     * @param lockedUntil  when the lockout naturally expires ({@code lockedAt} plus the configured duration)
     * @param failureCount the failure tally for the IP at the moment it tripped
     * @return the unpersisted record
     */
    public static IpLockout of(final String ipAddress, final Instant lockedAt, final Instant lockedUntil, final int failureCount) {
        final IpLockout lockout = new IpLockout();
        lockout.ipAddress = ipAddress;
        lockout.lockedAt = lockedAt;
        lockout.lockedUntil = lockedUntil;
        lockout.failureCount = failureCount;
        return lockout;
    }

    /**
     * One page of lockout rows at or after the retention cutoff, most recent first.
     *
     * @param cutoff    the oldest {@code lockedAt} to include (typically {@code now - HISTORY_RETENTION})
     * @param pageIndex the 0-based page index
     * @param pageSize  the page size
     * @return the requested page of rows
     */
    public static List<IpLockout> pageWithin(final Instant cutoff, final int pageIndex, final int pageSize) {
        return IpLockout.<IpLockout>find("lockedAt >= ?1", Sort.by("lockedAt").descending(), cutoff)
            .page(Page.of(pageIndex, pageSize))
            .list();
    }

    /**
     * Counts the lockout rows at or after the retention cutoff.
     *
     * @param cutoff the oldest {@code lockedAt} to include
     * @return the number of rows within the window
     */
    public static long countWithin(final Instant cutoff) {
        return count("lockedAt >= ?1", cutoff);
    }

    /**
     * Finds the still-active, not-yet-manually-unlocked rows for the given IP: {@code unlockedAt IS NULL} and the lockout not yet expired at
     * {@code now}. Normally this is a single row; it returns a list to stamp every matching row defensively.
     *
     * @param ipAddress the client IP
     * @param now       the current instant
     * @return the matching active rows (possibly empty)
     */
    public static List<IpLockout> findActiveByIp(final String ipAddress, final Instant now) {
        return list("ipAddress = ?1 and unlockedAt is null and lockedUntil > ?2", ipAddress, now);
    }

    /**
     * Deletes every lockout row older than the given cutoff, returning the number of rows removed.
     *
     * @param cutoff the oldest {@code lockedAt} to keep
     * @return the number of rows deleted
     */
    public static long deleteOlderThan(final Instant cutoff) {
        return delete("lockedAt < ?1", cutoff);
    }
}
