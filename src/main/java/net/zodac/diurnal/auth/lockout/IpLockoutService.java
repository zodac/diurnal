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

package net.zodac.diurnal.auth.lockout;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.zodac.diurnal.page.PageWindow;
import net.zodac.diurnal.page.Pages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of the per-IP lockout <em>history</em> and the administrative view over it: it records a durable {@link IpLockout} row whenever a
 * lockout trips, lists the currently-locked IPs and the last week of history, and manually unlocks an IP. The live lockout enforcement stays in
 * {@link IpThrottle}/{@link AttemptThrottle} (in-memory); this service is the seam where a tripped lockout is persisted and where an administrator
 * reads/clears it, shared by the admin page's HTMX endpoints and the REST API so the rules cannot diverge between the surfaces.
 *
 * <p>
 * Transaction discipline mirrors the hashing services: {@link #recordFailure(String, Instant)} runs on the (non-transactional) credential path, so
 * when a failure trips a lockout it persists the history row in its own short {@code self}-invoked transaction ({@link #persistLockout}). The
 * administrative reads ({@link #currentLockouts}, {@link #history}) need no transaction, and {@link #unlock} assumes the calling endpoint owns one
 * (the {@code AdminUserService} convention).
 */
@ApplicationScoped
public class IpLockoutService {

    private static final Logger LOGGER = LogManager.getLogger(IpLockoutService.class);

    private final Instance<IpLockoutService> self;
    private final IpThrottle ipThrottle;

    /**
     * Injects the per-IP throttle and a lazy self-reference. The self {@link Instance} resolves the CDI client proxy on demand so the short
     * {@code @Transactional} {@link #persistLockout} runs through the proxy (applying the interceptor) without a construction-time cycle.
     *
     * @param self       a lazy self-reference used to invoke the transactional {@code persistLockout} through the CDI proxy
     * @param ipThrottle the shared per-IP lockout
     */
    @Inject
    public IpLockoutService(final Instance<IpLockoutService> self, final IpThrottle ipThrottle) {
        this.self = self;
        this.ipThrottle = ipThrottle;
    }

    /**
     * Records a failed login or registration against the client IP and, when that failure is the one that trips the lockout, persists a durable
     * history row for it. A thin wrapper over {@link IpThrottle#recordFailure} so every credential surface records both the in-memory tally and the
     * history in one call.
     *
     * @param clientIp the client IP
     * @param now      the current instant (from {@code AppClock})
     * @return the throttle outcome (failure count, limit, whether this failure tripped the lockout, duration) — for the caller's audit logging
     */
    public AttemptThrottle.FailureOutcome recordFailure(final String clientIp, final Instant now) {
        final AttemptThrottle.FailureOutcome outcome = ipThrottle.recordFailure(clientIp, now);
        if (outcome.lockedOut()) {
            self.get().persistLockout(clientIp, now, outcome);
        }
        return outcome;
    }

    /**
     * Persists the history row for a just-tripped lockout in one short transaction, first pruning rows older than {@link IpLockout#HISTORY_RETENTION}
     * so the table stays bounded without a background sweeper. Invoked via the CDI proxy ({@code self}) from {@link #recordFailure}, which runs
     * outside any transaction on the credential path.
     *
     * @param clientIp the locked-out client IP
     * @param now      the instant the lockout tripped
     * @param outcome  the throttle outcome carrying the failure tally and the lockout duration
     */
    // Package-private is REQUIRED, not incidental: this runs through the CDI proxy so the @Transactional interceptor applies, and an
    // interceptor is never applied to a private method - narrowing it would silently drop the transaction while still compiling.
    @SuppressWarnings("WeakerAccess")
    @Transactional
    void persistLockout(final String clientIp, final Instant now, final AttemptThrottle.FailureOutcome outcome) {
        IpLockout.deleteOlderThan(now.minus(IpLockout.HISTORY_RETENTION));
        IpLockout.of(clientIp, now, now.plus(outcome.lockoutDuration()), outcome.failureCount()).persist();
        LOGGER.info("Recorded IP lockout history for {} (locked for {})", clientIp, outcome.lockoutDuration());
    }

    /**
     * The client IPs currently locked out at {@code now} (from the live in-memory enforcement state), each with its expiry and failure tally. This is
     * the actionable "currently locked out" list exposed by the public API ({@code GET /api/v1/admin/ip-lockouts}); the admin page instead surfaces a
     * single history table and offers the unlocking on its active rows.
     *
     * @param now the current instant
     * @return the currently-locked IPs (empty when none are locked or throttling is disabled)
     */
    public List<AttemptThrottle.ActiveLockout> currentLockouts(final Instant now) {
        return ipThrottle.currentLockouts(now);
    }

    /**
     * One page of lockout history within the retention window ({@link IpLockout#HISTORY_RETENTION}), most recent first, paged by the viewing
     * administrator's page-size preference. Rows outside the window are never returned (and are pruned as new lockouts are recorded).
     *
     * @param pageNum  the requested 1-based page (clamped into range)
     * @param pageSize the viewing administrator's page size
     * @param now      the current instant (defines the retention cutoff)
     * @return the requested page of history rows
     */
    public HistoryPage history(final int pageNum, final int pageSize, final Instant now) {
        final Instant cutoff = now.minus(IpLockout.HISTORY_RETENTION);
        final long totalCount = IpLockout.countWithin(cutoff);
        final PageWindow window = Pages.window(totalCount, pageNum, pageSize);

        final List<IpLockout> rows = IpLockout.pageWithin(cutoff, window.currentPage() - 1, pageSize);
        return new HistoryPage(rows, totalCount, window.totalPages(), window.currentPage());
    }

    /**
     * Finds a single lockout history row by its id, for the admin table's single-row confirm/restore re-renders. A presentation read; no transaction
     * is needed.
     *
     * @param id the lockout row id
     * @return the matching row, or {@code null} when none exists
     */
    @Nullable
    public IpLockout find(final UUID id) {
        return IpLockout.findById(id);
    }

    /**
     * Manually clears the lockout for the given client IP: removes the in-memory enforcement entry and stamps the matching active history row(s) as
     * unlocked. Reports {@link IpUnlockResult.NotLocked} when the IP was not actually locked (nothing to do). Assumes the calling endpoint owns the
     * transaction, so the stamped rows flush on its commit.
     *
     * @param actorEmail the administrator performing the unlocking, for the audit stamp and log
     * @param clientIp   the client IP to unlock
     * @param now        the current instant
     * @return the outcome
     */
    public IpUnlockResult unlock(final String actorEmail, final String clientIp, final Instant now) {
        if (!ipThrottle.isLocked(clientIp, now)) {
            return new IpUnlockResult.NotLocked();
        }
        ipThrottle.unlock(clientIp, now);
        for (final IpLockout row : IpLockout.findActiveByIp(clientIp, now)) {
            row.unlockedAt = now;
            row.unlockedBy = actorEmail;
        }
        LOGGER.info("Admin {} manually unlocked IP {}", actorEmail, clientIp);
        return new IpUnlockResult.Success(clientIp);
    }

    /**
     * One page of lockout history rows within the retention window, most recent first.
     *
     * @param rows        the page's rows
     * @param totalCount  the total number of rows within the window
     * @param totalPages  the page count
     * @param currentPage the returned (clamped) 1-based page
     */
    public record HistoryPage(List<IpLockout> rows, long totalCount, int totalPages, int currentPage) {

    }
}
