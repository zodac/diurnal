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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AttemptThrottle}, driving time explicitly so the fixed-window lockout, its boundaries, expiry, decay, and the
 * enabled/disabled switch are all deterministic.
 */
class AttemptThrottleTest {

    private static final String KEY = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - Test IP key
    private static final Instant T0 = Instant.parse("2026-06-15T12:00:00Z");
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LOCKOUT = Duration.ofMinutes(10);

    private static AttemptThrottle throttle(final boolean enabled) {
        return new AttemptThrottle(enabled, MAX_ATTEMPTS, LOCKOUT);
    }

    @Test
    void unknownKey_isNotLocked() {
        assertThat(throttle(true).isLocked(KEY, T0))
                .as("A never-seen key must not be locked")
                .isFalse();
    }

    @Test
    void belowThreshold_doesNotLock() {
        final AttemptThrottle throttle = throttle(true);
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            throttle.recordFailure(KEY, T0);
        }
        assertThat(throttle.isLocked(KEY, T0))
                .as("Key must stay unlocked below the failure threshold")
                .isFalse();
    }

    @Test
    void reachingThreshold_locksKey() {
        final AttemptThrottle throttle = throttle(true);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(KEY, T0);
        }
        assertThat(throttle.isLocked(KEY, T0))
                .as("Key must lock once the failure threshold is reached")
                .isTrue();
    }

    @Test
    void lockPersistsUntilJustBeforeExpiry() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.isLocked(KEY, T0.plus(LOCKOUT).minusSeconds(1)))
                .as("Key must remain locked right up to the expiry instant")
                .isTrue();
    }

    @Test
    void lockExpiresExactlyAtWindowEnd() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.isLocked(KEY, T0.plus(LOCKOUT)))
                .as("Key must be unlocked at the exact expiry instant")
                .isFalse();
    }

    @Test
    void afterExpiry_singleFailureDoesNotImmediatelyRelock() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        final Instant afterExpiry = T0.plus(LOCKOUT);
        throttle.recordFailure(KEY, afterExpiry);

        assertThat(throttle.isLocked(KEY, afterExpiry))
                .as("Failure counting must reset after a lockout elapses, not relock on the first new failure")
                .isFalse();
    }

    @Test
    void afterExpiry_thresholdFailuresRelock() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        final Instant afterExpiry = T0.plus(LOCKOUT);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(KEY, afterExpiry);
        }
        assertThat(throttle.isLocked(KEY, afterExpiry))
                .as("A fresh batch of failures after expiry must lock the key again")
                .isTrue();
    }

    @Test
    void lockoutRemaining_reportsFullWindowAtLockTime() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.lockoutRemaining(KEY, T0))
                .as("Remaining lockout at lock time must equal the configured window")
                .isEqualTo(LOCKOUT);
    }

    @Test
    void lockoutRemaining_shrinksAsTimePasses() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.lockoutRemaining(KEY, T0.plusSeconds(60)))
                .as("Remaining lockout must count down as time passes")
                .isEqualTo(LOCKOUT.minusSeconds(60));
    }

    @Test
    void lockoutRemaining_isZeroWhenNotLocked() {
        assertThat(throttle(true).lockoutRemaining(KEY, T0))
                .as("An unlocked key has zero remaining lockout")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void lockoutRemaining_isZeroAfterExpiry() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.lockoutRemaining(KEY, T0.plus(LOCKOUT)))
                .as("An elapsed lockout reports zero remaining")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void disabled_neverLocks() {
        final AttemptThrottle throttle = throttle(false);
        for (int i = 0; i < MAX_ATTEMPTS * 2; i++) {
            throttle.recordFailure(KEY, T0);
        }
        assertThat(throttle.isLocked(KEY, T0))
                .as("A disabled throttle must never lock, however many failures occur")
                .isFalse();
    }

    @Test
    void recordFailure_belowThreshold_reportsRunningCountAndNotLocked() {
        final AttemptThrottle throttle = throttle(true);

        final AttemptThrottle.FailureOutcome outcome = throttle.recordFailure(KEY, T0);

        assertThat(outcome.failureCount())
                .as("The first failure must report a count of one")
                .isEqualTo(1);
        assertThat(outcome.maxAttempts())
                .as("The outcome must carry the configured limit")
                .isEqualTo(MAX_ATTEMPTS);
        assertThat(outcome.lockedOut())
                .as("A sub-threshold failure must not report a lockout")
                .isFalse();
    }

    @Test
    void recordFailure_atThreshold_reportsLockout() {
        final AttemptThrottle throttle = throttle(true);
        throttle.recordFailure(KEY, T0);
        throttle.recordFailure(KEY, T0);

        final AttemptThrottle.FailureOutcome outcome = throttle.recordFailure(KEY, T0);

        assertThat(outcome.failureCount())
                .as("The lockout-tripping failure's count must equal the limit")
                .isEqualTo(MAX_ATTEMPTS);
        assertThat(outcome.lockedOut())
                .as("The failure that reaches the threshold must report a lockout")
                .isTrue();
    }

    @Test
    void recordFailure_whenDisabled_reportsZeroCountAndNoLockout() {
        final AttemptThrottle throttle = throttle(false);

        final AttemptThrottle.FailureOutcome outcome = throttle.recordFailure(KEY, T0);

        assertThat(outcome.failureCount())
                .as("A disabled throttle reports no tracked failures")
                .isEqualTo(0);
        assertThat(outcome.maxAttempts())
                .as("A disabled throttle still reports the configured limit")
                .isEqualTo(MAX_ATTEMPTS);
        assertThat(outcome.lockedOut())
                .as("A disabled throttle never reports a lockout")
                .isFalse();
    }

    @Test
    void recordFailure_reportsConfiguredLockoutDuration() {
        assertThat(throttle(true).recordFailure(KEY, T0).lockoutDuration())
                .as("The outcome must carry the configured lockout length for logging")
                .isEqualTo(LOCKOUT);
    }

    @Test
    void failuresSpacedBeyondWindow_decayAndDoNotAccumulate() {
        final AttemptThrottle throttle = throttle(true);
        // One failure per window+ elapsed: each is treated as fresh, so the count never climbs to lock.
        for (int i = 0; i < MAX_ATTEMPTS + 2; i++) {
            final Instant when = T0.plus(LOCKOUT.plusMinutes(1).multipliedBy(i));
            final AttemptThrottle.FailureOutcome outcome = throttle.recordFailure(KEY, when);
            assertThat(outcome.failureCount())
                    .as("A failure a full window after the previous one must reset the count to one")
                    .isEqualTo(1);
            assertThat(throttle.isLocked(KEY, when))
                    .as("Widely-spaced failures must never lock the key")
                    .isFalse();
        }
    }

    @Test
    void clear_forgetsAllAttempts() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        throttle.clear();

        assertThat(throttle.isLocked(KEY, T0))
                .as("clear() must forget the lockout")
                .isFalse();
    }

    private static void lockOut(final AttemptThrottle throttle) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(KEY, T0);
        }
    }
}
