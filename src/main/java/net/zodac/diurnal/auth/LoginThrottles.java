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
import net.zodac.diurnal.config.ThrottleConfig;

/**
 * Coordinates the two login throttles — per-account (keyed by email) and per-IP (keyed by client IP) —
 * that both login surfaces ({@link AuthResource} and {@link PasswordIdentityProvider}) consult.
 *
 * <p>
 * A login is blocked if <em>either</em> dimension is locked: the account throttle protects a specific
 * targeted account across every source IP (something a proxy can't do), while the IP throttle slows a
 * single host rotating through many accounts. A successful login clears the account counter but
 * deliberately <em>not</em> the IP counter — otherwise one valid login would reset an attacker's
 * whole-IP brute-force budget; the IP counter decays on its own after a quiet window.
 */
@ApplicationScoped
public class LoginThrottles {

    private final LoginThrottle accountThrottle;
    private final LoginThrottle ipThrottle;

    /**
     * Builds the two throttles from their config snapshots.
     *
     * @param accountConfig the per-account throttle settings
     * @param ipConfig      the per-IP throttle settings
     */
    @Inject
    public LoginThrottles(final ThrottleConfig accountConfig, final IpThrottleConfig ipConfig) {
        this.accountThrottle = new LoginThrottle(accountConfig.enabled(), accountConfig.maxAttempts(), accountConfig.lockoutDuration());
        this.ipThrottle = new LoginThrottle(ipConfig.enabled(), ipConfig.maxAttempts(), ipConfig.lockoutDuration());
    }

    /**
     * Whether this login must be rejected without checking the password — because either the account or
     * the client IP is currently locked out.
     *
     * @param email    the (already-normalised) submitted email
     * @param clientIp the client IP
     * @param now      the current instant
     * @return {@code true} when either dimension is locked
     */
    public boolean isLocked(final String email, final String clientIp, final Instant now) {
        return accountThrottle.isLocked(email, now) || ipThrottle.isLocked(clientIp, now);
    }

    /**
     * Records a failed login against both dimensions.
     *
     * @param email    the (already-normalised) submitted email
     * @param clientIp the client IP
     * @param now      the current instant
     * @return the per-account and per-IP outcomes, for logging
     */
    public ThrottleOutcome recordFailure(final String email, final String clientIp, final Instant now) {
        return new ThrottleOutcome(accountThrottle.recordFailure(email, now), ipThrottle.recordFailure(clientIp, now));
    }

    /**
     * Records a successful login: clears the account counter, but leaves the IP counter to decay.
     *
     * @param email the (already-normalised) submitted email
     */
    public void recordSuccess(final String email) {
        accountThrottle.recordSuccess(email);
    }

    /**
     * The time until this login could next succeed — the longer of the two lockouts, since both must
     * clear. {@link Duration#ZERO} when neither is locked.
     *
     * @param email    the (already-normalised) submitted email
     * @param clientIp the client IP
     * @param now      the current instant
     * @return the remaining lockout, never negative
     */
    public Duration lockoutRemaining(final String email, final String clientIp, final Instant now) {
        final long account = accountThrottle.lockoutRemaining(email, now).toSeconds();
        final long ip = ipThrottle.lockoutRemaining(clientIp, now).toSeconds();
        return Duration.ofSeconds(Math.max(account, ip));
    }

    /** @return the per-account throttle (test support). */
    LoginThrottle account() {
        return accountThrottle;
    }

    /** @return the per-IP throttle (test support). */
    LoginThrottle ip() {
        return ipThrottle;
    }

    /**
     * Forgets all tracked attempts in both throttles (test support).
     */
    void clear() {
        accountThrottle.clear();
        ipThrottle.clear();
    }

    /**
     * The combined result of recording a failed login — the per-account and per-IP outcomes.
     *
     * @param account the per-account outcome
     * @param ip      the per-IP outcome
     */
    public record ThrottleOutcome(LoginThrottle.FailureOutcome account, LoginThrottle.FailureOutcome ip) {
    }
}
