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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * In-memory, fixed-window login throttle over an opaque string key: after {@code maxAttempts}
 * failures for a key within the window, that key is locked out for {@code lockoutDuration}.
 *
 * <p>
 * The class is key-agnostic — {@link LoginThrottles} runs two instances, one keyed by submitted email
 * (per-account) and one by client IP (per-host). Keying by the <em>submitted</em> value (whether or
 * not it corresponds to a real account) means an attacker cannot distinguish a real account from an
 * unknown one by watching for throttling (account enumeration).
 *
 * <p>
 * Time is passed in by the caller (from {@code AppClock.now()}) rather than read here, so the logic is
 * pure and deterministically unit-testable, and integration tests can freeze/advance the clock. Config
 * is snapshotted at construction (Quarkus {@code @ConfigMapping} values are fixed for the run).
 *
 * <p>
 * State is held in a {@link ConcurrentHashMap} and mutated only inside {@link ConcurrentHashMap#compute}
 * (which locks the bin), so concurrent logins for the same key are consistent. A counter <em>decays</em>:
 * a fresh failure that arrives more than one window after the previous one starts over, so a shared key
 * (e.g. a NAT'd IP) never accumulates unrelated failures indefinitely. A successful login or a restart
 * also drops the entry.
 */
public final class LoginThrottle {

    private final boolean enabled;
    private final int maxAttempts;
    private final Duration lockoutDuration;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * Builds a throttle from a config snapshot.
     *
     * @param enabled         whether throttling is active at all
     * @param maxAttempts     failures tolerated within the window before lockout
     * @param lockoutDuration both the lockout length and the decay window
     */
    public LoginThrottle(final boolean enabled, final int maxAttempts, final Duration lockoutDuration) {
        this.enabled = enabled;
        this.maxAttempts = maxAttempts;
        this.lockoutDuration = lockoutDuration;
    }

    /**
     * Whether the given key is currently locked out and must be rejected without checking the password.
     * Always {@code false} when throttling is disabled.
     *
     * @param key the throttle key (submitted email, or client IP)
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
     * Records a failed login for the given key, locking it out once {@code maxAttempts} failures are
     * reached within the window. A no-op when throttling is disabled.
     *
     * @param key the throttle key (submitted email, or client IP)
     * @param now the current instant
     * @return the outcome (failure count, configured limit, whether this failure tripped the lockout,
     *     and the lockout length) — for logging
     */
    public FailureOutcome recordFailure(final String key, final Instant now) {
        if (!enabled) {
            return new FailureOutcome(0, maxAttempts, false, lockoutDuration);
        }
        final Attempt updated = Objects.requireNonNull(attempts.compute(key, (k, existing) -> {
            // Start fresh if there is no prior record, or the previous activity is older than one window
            // (a lapsed lockout, or a quiet spell for a still-counting key).
            final Attempt attempt = existing == null || existing.isStaleAt(now, lockoutDuration) ? new Attempt() : existing;
            attempt.failureCount++;
            attempt.lastFailureAt = now;
            if (attempt.failureCount >= maxAttempts) {
                attempt.lockedUntil = now.plus(lockoutDuration);
            }
            return attempt;
        }));
        return new FailureOutcome(updated.failureCount, maxAttempts, updated.isLockedAt(now), lockoutDuration);
    }

    /**
     * Records a successful login, clearing any tracked failures/lockout for the key.
     *
     * @param key the throttle key (submitted email, or client IP)
     */
    public void recordSuccess(final String key) {
        attempts.remove(key);
    }

    /**
     * How much longer the given key is locked out, or {@link Duration#ZERO} when it is not locked.
     *
     * @param key the throttle key (submitted email, or client IP)
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
     * Forgets all tracked attempts. Test-support hook so an integration test can start from a clean
     * slate; production code never calls this.
     */
    void clear() {
        attempts.clear();
    }

    /**
     * The result of recording a failed login: how many failures the key now has in the window, the
     * configured limit, whether this failure tripped the lockout, and how long that lockout lasts.
     * Consumed only for logging.
     *
     * @param failureCount    the failure count after this failure ({@code 0} when throttling is disabled)
     * @param maxAttempts     the configured number of failures tolerated before lockout
     * @param lockedOut       {@code true} if this failure is the one that locked the key
     * @param lockoutDuration the configured lockout length
     */
    public record FailureOutcome(int failureCount, int maxAttempts, boolean lockedOut, Duration lockoutDuration) {
    }

    /*
     * Mutable per-key tally: failures in the window, the last failure instant (for decay), and — once
     * locked — the instant the lockout ends.
     */
    private static final class Attempt {

        private int failureCount;

        // Non-null default so isStaleAt needs no null guard; overwritten by the first recordFailure, and
        // only ever read on an entry that has already had one.
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
