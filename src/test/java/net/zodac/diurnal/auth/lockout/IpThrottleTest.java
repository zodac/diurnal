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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link IpThrottle}, verifying its per-IP delegation to the shared throttle: failures accumulate to a lockout, the remaining time is
 * reported, and there is no success hook to launder the counter (it only clears by decay).
 */
class IpThrottleTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final String DUMMY_IP = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - Test IP

    private static IpThrottle throttle() {
        return new IpThrottle(new FixedConfig(true, MAX_ATTEMPTS, LOCKOUT));
    }

    @Test
    void unknownIp_isNotLocked() {
        assertThat(throttle().isLocked(DUMMY_IP, NOW))
                .as("A never-seen IP must not be locked")
                .isFalse();
    }

    @Test
    void reachingThreshold_locksIp() {
        final IpThrottle throttle = throttle();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(DUMMY_IP, NOW);
        }

        assertThat(throttle.isLocked(DUMMY_IP, NOW))
                .as("Reaching the failure threshold must lock the IP")
                .isTrue();
    }

    @Test
    void recordFailure_reportsLimitAndCount() {
        final AttemptThrottle.FailureOutcome outcome = throttle().recordFailure(DUMMY_IP, NOW);

        assertThat(outcome.maxAttempts())
                .as("The outcome must carry the configured limit")
                .isEqualTo(MAX_ATTEMPTS);
        assertThat(outcome.failureCount())
                .as("The first failure must report a count of one")
                .isEqualTo(1);
    }

    @Test
    void lockoutRemaining_whenLocked_isTheWindow() {
        final IpThrottle throttle = throttle();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(DUMMY_IP, NOW);
        }

        assertThat(throttle.lockoutRemaining(DUMMY_IP, NOW))
                .as("A locked IP must report the full lockout window as remaining")
                .isEqualTo(LOCKOUT);
    }

    @Test
    void lockoutRemaining_whenNotLocked_isZero() {
        assertThat(throttle().lockoutRemaining(DUMMY_IP, NOW))
                .as("An unlocked IP has no wait")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void disabled_neverLocks() {
        final IpThrottle throttle = new IpThrottle(new FixedConfig(false, MAX_ATTEMPTS, LOCKOUT));
        for (int i = 0; i < MAX_ATTEMPTS + 2; i++) {
            throttle.recordFailure(DUMMY_IP, NOW);
        }

        assertThat(throttle.isLocked(DUMMY_IP, NOW))
                .as("A disabled throttle must never lock, no matter how many failures")
                .isFalse();
    }

    @Test
    void currentLockouts_listsTheLockedIp() {
        final IpThrottle throttle = throttle();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(DUMMY_IP, NOW);
        }

        assertThat(throttle.currentLockouts(NOW))
                .as("The locked IP must be reported with its expiry and failure count")
                .containsExactly(new AttemptThrottle.ActiveLockout(DUMMY_IP, NOW.plus(LOCKOUT), MAX_ATTEMPTS));
    }

    @Test
    void currentLockouts_emptyWhenNothingLocked() {
        assertThat(throttle().currentLockouts(NOW))
                .as("No IPs locked means no current lockouts")
                .isEmpty();
    }

    @Test
    void unlock_clearsTheLockout() {
        final IpThrottle throttle = throttle();
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(DUMMY_IP, NOW);
        }

        assertThat(throttle.unlock(DUMMY_IP, NOW))
                .as("Unlocking a locked IP must report it was locked")
                .isTrue();
        assertThat(throttle.isLocked(DUMMY_IP, NOW))
                .as("The IP must no longer be locked after an unlock")
                .isFalse();
    }

    @Test
    void unlock_unknownIp_reportsFalse() {
        assertThat(throttle().unlock(DUMMY_IP, NOW))
                .as("Unlocking a never-seen IP reports it was not locked")
                .isFalse();
    }

    private record FixedConfig(boolean enabled, int maxAttempts, Duration lockoutDuration) implements IpThrottleConfig {

    }
}
