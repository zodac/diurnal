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
 * Unit tests for {@link LoginThrottle}, driving time explicitly so the fixed-window lockout, its
 * boundaries, expiry, and the enabled/disabled switch are all deterministic.
 */
class LoginThrottleTest {

    private static final String EMAIL = "victim@example.com";
    private static final Instant T0 = Instant.parse("2026-06-15T12:00:00Z");
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration LOCKOUT = Duration.ofMinutes(10);

    private static LoginThrottle throttle(final boolean enabled) {
        return new LoginThrottle(enabled, MAX_ATTEMPTS, LOCKOUT);
    }

    @Test
    void unknownAccount_isNotLocked() {
        assertThat(throttle(true).isLocked(EMAIL, T0))
                .as("A never-seen account must not be locked")
                .isFalse();
    }

    @Test
    void belowThreshold_doesNotLock() {
        final LoginThrottle throttle = throttle(true);
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            throttle.recordFailure(EMAIL, T0);
        }
        assertThat(throttle.isLocked(EMAIL, T0))
                .as("Account must stay unlocked below the failure threshold")
                .isFalse();
    }

    @Test
    void reachingThreshold_locksAccount() {
        final LoginThrottle throttle = throttle(true);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(EMAIL, T0);
        }
        assertThat(throttle.isLocked(EMAIL, T0))
                .as("Account must lock once the failure threshold is reached")
                .isTrue();
    }

    @Test
    void lockPersistsUntilJustBeforeExpiry() {
        final LoginThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.isLocked(EMAIL, T0.plus(LOCKOUT).minusSeconds(1)))
                .as("Account must remain locked right up to the expiry instant")
                .isTrue();
    }

    @Test
    void lockExpiresExactlyAtWindowEnd() {
        final LoginThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.isLocked(EMAIL, T0.plus(LOCKOUT)))
                .as("Account must be unlocked at the exact expiry instant")
                .isFalse();
    }

    @Test
    void afterExpiry_asingleFailureDoesNotImmediatelyRelock() {
        final LoginThrottle throttle = throttle(true);
        lockOut(throttle);

        final Instant afterExpiry = T0.plus(LOCKOUT);
        throttle.recordFailure(EMAIL, afterExpiry);

        assertThat(throttle.isLocked(EMAIL, afterExpiry))
                .as("Failure counting must reset after a lockout elapses, not relock on the first new failure")
                .isFalse();
    }

    @Test
    void afterExpiry_thresholdFailuresRelock() {
        final LoginThrottle throttle = throttle(true);
        lockOut(throttle);

        final Instant afterExpiry = T0.plus(LOCKOUT);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(EMAIL, afterExpiry);
        }
        assertThat(throttle.isLocked(EMAIL, afterExpiry))
                .as("A fresh batch of failures after expiry must lock the account again")
                .isTrue();
    }

    @Test
    void recordSuccess_clearsAccumulatedFailures() {
        final LoginThrottle throttle = throttle(true);
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            throttle.recordFailure(EMAIL, T0);
        }
        throttle.recordSuccess(EMAIL);
        throttle.recordFailure(EMAIL, T0);

        assertThat(throttle.isLocked(EMAIL, T0))
                .as("A successful login must reset the failure count so prior failures no longer count")
                .isFalse();
    }

    @Test
    void lockoutRemaining_reportsFullWindowAtLockTime() {
        final LoginThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.lockoutRemaining(EMAIL, T0))
                .as("Remaining lockout at lock time must equal the configured window")
                .isEqualTo(LOCKOUT);
    }

    @Test
    void lockoutRemaining_shrinksAsTimePasses() {
        final LoginThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.lockoutRemaining(EMAIL, T0.plusSeconds(60)))
                .as("Remaining lockout must count down as time passes")
                .isEqualTo(LOCKOUT.minusSeconds(60));
    }

    @Test
    void lockoutRemaining_isZeroWhenNotLocked() {
        assertThat(throttle(true).lockoutRemaining(EMAIL, T0))
                .as("An unlocked account has zero remaining lockout")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void lockoutRemaining_isZeroAfterExpiry() {
        final LoginThrottle throttle = throttle(true);
        lockOut(throttle);

        assertThat(throttle.lockoutRemaining(EMAIL, T0.plus(LOCKOUT)))
                .as("An elapsed lockout reports zero remaining")
                .isEqualTo(Duration.ZERO);
    }

    @Test
    void disabled_neverLocks() {
        final LoginThrottle throttle = throttle(false);
        for (int i = 0; i < MAX_ATTEMPTS * 2; i++) {
            throttle.recordFailure(EMAIL, T0);
        }
        assertThat(throttle.isLocked(EMAIL, T0))
                .as("A disabled throttle must never lock, however many failures occur")
                .isFalse();
    }

    @Test
    void recordFailure_belowThreshold_reportsRunningCountAndNotLocked() {
        final LoginThrottle throttle = throttle(true);

        final LoginThrottle.FailureOutcome outcome = throttle.recordFailure(EMAIL, T0);

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
        final LoginThrottle throttle = throttle(true);
        throttle.recordFailure(EMAIL, T0);
        throttle.recordFailure(EMAIL, T0);

        final LoginThrottle.FailureOutcome outcome = throttle.recordFailure(EMAIL, T0);

        assertThat(outcome.failureCount())
                .as("The lockout-tripping failure's count must equal the limit")
                .isEqualTo(MAX_ATTEMPTS);
        assertThat(outcome.lockedOut())
                .as("The failure that reaches the threshold must report a lockout")
                .isTrue();
    }

    @Test
    void recordFailure_whenDisabled_reportsZeroCountAndNoLockout() {
        final LoginThrottle throttle = throttle(false);

        final LoginThrottle.FailureOutcome outcome = throttle.recordFailure(EMAIL, T0);

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
        assertThat(throttle(true).recordFailure(EMAIL, T0).lockoutDuration())
                .as("The outcome must carry the configured lockout length for logging")
                .isEqualTo(LOCKOUT);
    }

    @Test
    void failuresSpacedBeyondWindow_decayAndDoNotAccumulate() {
        final LoginThrottle throttle = throttle(true);
        // One failure per window+ elapsed: each is treated as fresh, so the count never climbs to lock.
        for (int i = 0; i < MAX_ATTEMPTS + 2; i++) {
            final Instant when = T0.plus(LOCKOUT.plusMinutes(1).multipliedBy(i));
            final LoginThrottle.FailureOutcome outcome = throttle.recordFailure(EMAIL, when);
            assertThat(outcome.failureCount())
                    .as("A failure a full window after the previous one must reset the count to one")
                    .isEqualTo(1);
            assertThat(throttle.isLocked(EMAIL, when))
                    .as("Widely-spaced failures must never lock the key")
                    .isFalse();
        }
    }

    @Test
    void clear_forgetsAllAttempts() {
        final LoginThrottle throttle = throttle(true);
        lockOut(throttle);

        throttle.clear();

        assertThat(throttle.isLocked(EMAIL, T0))
                .as("clear() must forget the lockout")
                .isFalse();
    }

    private static void lockOut(final LoginThrottle throttle) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            throttle.recordFailure(EMAIL, T0);
        }
    }
}
