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
 * Unit tests for {@link AttemptThrottle}, driving time explicitly so the fixed-window lockout, its boundaries, expiry, decay, and the
 * enabled/disabled switch are all deterministic.
 */
class AttemptThrottleTest {

    private static final String KEY = "203.0.113.7"; // NOPMD: AvoidUsingHardCodedIP - Test IP key
    private static final String KEY2 = "198.51.100.9"; // NOPMD: AvoidUsingHardCodedIP - Test IP key
    private static final Instant T0 = Instant.parse("2026-06-15T12:00:00Z");
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LOCKOUT = Duration.ofMinutes(10L);

    private static AttemptThrottle throttle(final boolean enabled) {
        return AttemptThrottle.create(enabled, MAX_ATTEMPTS, LOCKOUT);
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

        assertThat(throttle.isLocked(KEY, T0.plus(LOCKOUT).minusSeconds(1L)))
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

        assertThat(throttle.lockoutRemaining(KEY, T0.plusSeconds(60L)))
                .as("Remaining lockout must count down as time passes")
                .isEqualTo(LOCKOUT.minusSeconds(60L));
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
            final Instant when = T0.plus(LOCKOUT.plusMinutes(1L).multipliedBy(i));
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

    @Test
    void currentLockouts_emptyWhenNoneLocked() {
        final AttemptThrottle throttle = throttle(true);
        throttle.recordFailure(KEY, T0); // sub-threshold: counting but not locked

        assertThat(throttle.currentLockouts(T0))
                .as("A key below the threshold must not appear as a current lockout")
                .isEmpty();
    }

    @Test
    void currentLockouts_listsLockedKeyWithExpiryAndCount() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.currentLockouts(T0))
                .as("A locked key must be listed with its expiry and failure count")
                .containsExactly(new AttemptThrottle.ActiveLockout(KEY, T0.plus(LOCKOUT), MAX_ATTEMPTS));
    }

    @Test
    void currentLockouts_excludesExpiredLockout() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.currentLockouts(T0.plus(LOCKOUT)))
                .as("A lockout that has reached its expiry must not be listed as current")
                .isEmpty();
    }

    @Test
    void currentLockouts_listsOnlyTheLockedKeysAmongMany() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);
        throttle.recordFailure(KEY2, T0); // KEY2 stays below the threshold

        assertThat(throttle.currentLockouts(T0))
                .as("Only keys actually locked must be listed")
                .containsExactly(new AttemptThrottle.ActiveLockout(KEY, T0.plus(LOCKOUT), MAX_ATTEMPTS));
    }

    @Test
    void currentLockouts_emptyWhenDisabled() {
        final AttemptThrottle throttle = throttle(false);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(KEY, T0);
        }

        assertThat(throttle.currentLockouts(T0))
                .as("A disabled throttle never reports current lockouts")
                .isEmpty();
    }

    @Test
    void unlock_clearsLockAndReportsWasLocked() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.unlock(KEY, T0))
                .as("Unlocking a locked key must report that it was locked")
                .isTrue();
        assertThat(throttle.isLocked(KEY, T0))
                .as("The key must no longer be locked after an unlock")
                .isFalse();
    }

    @Test
    void unlock_untrackedKey_reportsFalse() {
        assertThat(throttle(true).unlock(KEY, T0))
                .as("Unlocking a never-seen key reports it was not locked")
                .isFalse();
    }

    @Test
    void unlock_subThresholdKey_reportsFalseButClearsTheCount() {
        final AttemptThrottle throttle = throttle(true);
        throttle.recordFailure(KEY, T0);
        throttle.recordFailure(KEY, T0); // below the threshold

        assertThat(throttle.unlock(KEY, T0))
                .as("Unlocking a key that was only counting (not locked) reports it was not locked")
                .isFalse();
        // The counter was cleared, so it now takes the full threshold again to lock.
        throttle.recordFailure(KEY, T0);
        assertThat(throttle.isLocked(KEY, T0))
                .as("A single failure after unlock must not relock a previously-counting key")
                .isFalse();
    }

    @Test
    void unlock_expiredLockout_reportsFalse() {
        final AttemptThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.unlock(KEY, T0.plus(LOCKOUT)))
                .as("Unlocking an already-expired lockout reports it was not locked")
                .isFalse();
    }

    @Test
    void unlock_whenDisabled_reportsFalse() {
        final AttemptThrottle throttle = throttle(false);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(KEY, T0);
        }

        assertThat(throttle.unlock(KEY, T0))
                .as("A disabled throttle has nothing to unlock")
                .isFalse();
    }

    private static void lockOut(final AttemptThrottle throttle) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(KEY, T0);
        }
    }
}
