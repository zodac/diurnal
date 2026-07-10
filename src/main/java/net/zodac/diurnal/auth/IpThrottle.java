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
import java.time.Duration;
import java.time.Instant;
import net.zodac.diurnal.config.IpThrottleConfig;

/**
 * The single, global per-IP lockout consulted by <em>every</em> credential surface — the login form,
 * {@code POST /api/auth/login}, the registration form and {@code POST /api/auth/register}. One shared
 * counter per client IP tallies both failed logins and failed registrations together; once it reaches the
 * configured limit within the window, that IP is locked out of <em>both</em> logging in and registering.
 *
 * <p>
 * There is deliberately <strong>no per-account (email) dimension</strong>: keying a lockout on the
 * submitted email lets an attacker deny service to a chosen victim by failing logins for their address, so
 * the throttle is keyed purely on the client IP. The trade-off (a distributed, many-IP brute-force against
 * one account) is mitigated by Argon2id hashing and uniform response timing, not by account lockouts.
 *
 * <p>
 * A success never clears the counter (it decays on its own after a quiet window), so neither a valid login
 * nor a throwaway registration can reset an IP's brute-force budget or launder attempts between the two
 * pages. The client IP comes from {@link ClientAddress} → Vert.x {@code remoteAddress()}, which honours
 * {@code TRUST_X_FORWARDED_HEADERS}, so this is only meaningful behind a trusted proxy. State is in-memory
 * (resets on restart, not shared across instances) — acceptable for the single-instance deployment. Time
 * is passed in from {@code AppClock.now()} so the logic stays pure and testable.
 */
@ApplicationScoped
public class IpThrottle {

    private final AttemptThrottle throttle;

    /**
     * Builds the throttle from its config snapshot.
     *
     * @param config the per-IP throttle settings
     */
    @Inject
    public IpThrottle(final IpThrottleConfig config) {
        this.throttle = new AttemptThrottle(config.enabled(), config.maxAttempts(), config.lockoutDuration());
    }

    /**
     * Whether the given client IP is currently locked out and must be rejected without checking
     * credentials.
     *
     * @param clientIp the client IP
     * @param now      the current instant
     * @return {@code true} when the IP is locked out at {@code now}
     */
    public boolean isLocked(final String clientIp, final Instant now) {
        return throttle.isLocked(clientIp, now);
    }

    /**
     * Records a failed login or registration against the client IP.
     *
     * @param clientIp the client IP
     * @param now      the current instant
     * @return the outcome (failure count, limit, whether this failure tripped the lockout, duration)
     */
    public AttemptThrottle.FailureOutcome recordFailure(final String clientIp, final Instant now) {
        return throttle.recordFailure(clientIp, now);
    }

    /**
     * How much longer the given IP is locked out, or {@link Duration#ZERO} when it is not locked.
     *
     * @param clientIp the client IP
     * @param now      the current instant
     * @return the remaining lockout duration, never negative
     */
    public Duration lockoutRemaining(final String clientIp, final Instant now) {
        return throttle.lockoutRemaining(clientIp, now);
    }

    /**
     * @return the underlying throttle (test support)
     */
    AttemptThrottle throttle() {
        return throttle;
    }

    /**
     * Forgets all tracked attempts (test support).
     */
    void clear() {
        throttle.clear();
    }
}
