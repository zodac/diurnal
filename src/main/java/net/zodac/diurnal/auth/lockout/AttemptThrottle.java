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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * In-memory, fixed-window attempt throttle over an opaque string key: after {@code maxAttempts} failures for a key within the window, that key is
 * locked out for {@code lockoutDuration}. This is the single shared lockout primitive; {@link IpThrottle} runs one instance keyed by client IP to
 * gate <em>both</em> failed logins and failed registrations from a single host.
 *
 * <p>
 * The class is key-agnostic (nothing here knows what the key represents), so the same logic backs every lockout without duplication. There is
 * deliberately no "success clears the counter" hook: a valid login or registration must not reset an IP's brute-force budget (otherwise one success
 * would launder an attacker's whole tally), so the counter only ever clears by decaying after a quiet window.
 *
 * <p>
 * Time is passed in by the caller (from {@code AppClock.now()}) rather than read here, so the logic is pure and deterministically unit-testable, and
 * integration tests can freeze/advance the clock. Config is snapshot at construction (Quarkus {@code @ConfigMapping} values are fixed for the run).
 *
 * <p>
 * State is held in a {@link ConcurrentHashMap} and mutated only inside {@link ConcurrentHashMap#compute} (which locks the bin), so concurrent
 * attempts for the same key are consistent. A counter <em>decays</em>: a fresh failure that arrives more than one window after the previous one
 * starts over, so a shared key (e.g. a NAT'd IP) never accumulates unrelated failures indefinitely. A restart also drops the entry.
 */
// AccessingNonPublicFieldOfAnotherObject: Attempt is a private nested mutable holder, read and written directly by the methods below - the
// inspection's ignoreInnerClasses option only covers the opposite direction (an inner class reading its enclosing class's fields).
@SuppressWarnings("AccessingNonPublicFieldOfAnotherObject")
public final class AttemptThrottle {

    // Read only as `!enabled`, which BooleanVariableAlwaysNegated objects to - but the inverse name it implies,
    // `disabled`, is what NegativelyNamedBooleanVariable objects to. Every read is a guard clause returning the
    // no-op answer, so satisfying the first would mean wrapping four method bodies in `if (enabled) { ... }`.
    @SuppressWarnings("BooleanVariableAlwaysNegated")
    private final boolean enabled;
    private final int maxAttempts;
    private final Duration lockoutDuration;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private AttemptThrottle(final boolean enabled, final int maxAttempts, final Duration lockoutDuration) {
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
        this.lockoutDuration = lockoutDuration;
    }

    /**
     * Builds a throttle from a config snapshot.
     *
     * @param enabled         whether throttling is enabled
     * @param maxAttempts     failures tolerated within the window before lockout
     * @param lockoutDuration both the lockout length and the decay window
     * @return the throttle
     */
    public static AttemptThrottle create(final boolean enabled, final int maxAttempts, final Duration lockoutDuration) {
        return new AttemptThrottle(enabled, maxAttempts, lockoutDuration);
    }

    /**
     * Whether the given key is currently locked out and must be rejected without checking credentials. Always {@code false} when throttling is
     * disabled.
     *
     * @param key the throttle key (the client IP)
     * @param now the current instant
     * @return {@code true} when the key is locked out at {@code now}
     */
    public boolean isLocked(final String key, final Instant now) {
        if (!enabled) {
            return false;
        }

        final Attempt attempt = attempts.get(key);
        return attempt != null && attempt.isLockedAt(now);
    }

    /**
     * Records a failed attempt for the given key, locking it out once {@code maxAttempts} failures are reached within the window. A no-op when
     * throttling is disabled.
     *
     * @param key the throttle key (the client IP)
     * @param now the current instant
     * @return the outcome (failure count, configured limit, whether this failure tripped the lockout, and the lockout length) — for logging
     */
    public FailureOutcome recordFailure(final String key, final Instant now) {
        if (!enabled) {
            return new FailureOutcome(0, maxAttempts, false, lockoutDuration);
        }

        final Attempt updated = Objects.requireNonNull(attempts.compute(key, (_, existing) -> countFailure(existing, now)));
        return new FailureOutcome(updated.failureCount, maxAttempts, updated.isLockedAt(now), lockoutDuration);
    }

    private Attempt countFailure(final @Nullable Attempt existing, final Instant now) {
        // Start fresh if there is no prior record, or the previous activity is older than one window
        // (a lapsed lockout, or a quiet spell for a still-counting key).
        final Attempt attempt = existing == null || existing.isStaleAt(now, lockoutDuration) ? new Attempt() : existing;
        attempt.failureCount++;
        attempt.lastFailureAt = now;
        if (attempt.failureCount >= maxAttempts) {
            attempt.lockedUntil = now.plus(lockoutDuration);
        }
        return attempt;
    }

    /**
     * How much longer the given key is locked out, or {@link Duration#ZERO} when it is not locked.
     *
     * @param key the throttle key (the client IP)
     * @param now the current instant
     * @return the remaining lockout duration, never negative
     */
    public Duration lockoutRemaining(final String key, final Instant now) {
        final Attempt attempt = attempts.get(key);
        if (attempt == null || !attempt.isLockedAt(now)) {
            return Duration.ZERO;
        }
        return Duration.between(now, attempt.lockedUntil);
    }

    /**
     * The keys currently locked out at {@code now}, each with its expiry and failure tally. Always empty when throttling is disabled. A snapshot of
     * the live in-memory state, so it reflects exactly what the enforcement path would reject right now (and resets on restart with that state).
     *
     * @param now the current instant
     * @return the currently-locked keys (never {@code null}; empty when none are locked or throttling is off)
     */
    public List<ActiveLockout> currentLockouts(final Instant now) {
        if (!enabled) {
            return List.of();
        }

        final List<ActiveLockout> locked = new ArrayList<>();
        attempts.forEach((key, attempt) -> {
            if (attempt.isLockedAt(now)) {
                locked.add(new ActiveLockout(key, Objects.requireNonNull(attempt.lockedUntil), attempt.failureCount));
            }
        });
        return List.copyOf(locked);
    }

    /**
     * Manually clears all tracked state for the given key, so it is no longer locked out and its failure counter starts fresh. A no-op when
     * throttling is disabled or the key is untracked.
     *
     * @param key the throttle key (the client IP) to clear
     * @return {@code true} if the key was actually locked out at the moment it was cleared
     */
    public boolean unlock(final String key, final Instant now) {
        if (!enabled) {
            return false;
        }

        final Attempt removed = attempts.remove(key);
        return removed != null && removed.isLockedAt(now);
    }

    /**
     * Forgets all tracked attempts. Test-support hook so an integration test can start from a clean slate; production code never calls this.
     */
    void clear() {
        attempts.clear();
    }

    /**
     * The result of recording a failed attempt: how many failures the key now has in the window, the configured limit, whether this failure tripped
     * the lockout, and how long that lockout lasts. Consumed only for logging.
     *
     * @param failureCount    the failure count after this failure ({@code 0} when throttling is disabled)
     * @param maxAttempts     the configured number of failures tolerated before lockout
     * @param lockedOut       {@code true} if this failure is the one that locked the key
     * @param lockoutDuration the configured lockout length
     */
    public record FailureOutcome(int failureCount, int maxAttempts, boolean lockedOut, Duration lockoutDuration) {

    }

    /**
     * A key that is locked out right now: its identity, when the lockout expires, and the failure tally that tripped it. Consumed by the admin
     * "currently locked out" view.
     *
     * @param key          the locked key (the client IP)
     * @param lockedUntil  the instant the lockout expires
     * @param failureCount the failure count recorded for the key
     */
    public record ActiveLockout(String key, Instant lockedUntil, int failureCount) {

    }

    private static final class Attempt {

        private int failureCount;

        // Non-null default so isStaleAt needs no null guard; overwritten by the first recordFailure
        private Instant lastFailureAt = Instant.EPOCH;

        @Nullable
        private Instant lockedUntil;

        private boolean isLockedAt(final Instant now) {
            return lockedUntil != null && now.isBefore(lockedUntil);
        }

        // Stale once a full window has elapsed since the last failure — covers both a lapsed lockout
        // (lockedUntil == lastFailureAt + window) and a quiet spell on a still-counting key.
        private boolean isStaleAt(final Instant now, final Duration window) {
            return !now.isBefore(lastFailureAt.plus(window));
        }
    }
}
