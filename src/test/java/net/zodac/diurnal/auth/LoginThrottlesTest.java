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
import net.zodac.diurnal.config.IpThrottleConfig;
import net.zodac.diurnal.config.ThrottleConfig;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LoginThrottles}, verifying the account+IP dimensions are combined correctly:
 * either dimension can block, success clears only the account, and the remaining time is the longer wall.
 */
class LoginThrottlesTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final int ACCOUNT_MAX = 2;
    private static final int IP_MAX = 5;
    private static final Duration ACCOUNT_LOCKOUT = Duration.ofMinutes(10);
    private static final Duration IP_LOCKOUT = Duration.ofMinutes(20);
    private static final String IP = "203.0.113.7";

    private static LoginThrottles throttles() {
        return new LoginThrottles(
                new FixedThrottleConfig(true, ACCOUNT_MAX, ACCOUNT_LOCKOUT),
                new FixedIpThrottleConfig(true, IP_MAX, IP_LOCKOUT));
    }

    private static void failAccountToLock(final LoginThrottles throttles, final String email, final String ip) {
        for (int i = 0; i < ACCOUNT_MAX; i++) {
            throttles.recordFailure(email, ip, NOW);
        }
    }

    private static void failIpToLock(final LoginThrottles throttles, final String ip) {
        // Distinct emails so no single account locks — only the shared IP does.
        for (int i = 0; i < IP_MAX; i++) {
            throttles.recordFailure("user" + i + "@example.com", ip, NOW);
        }
    }

    @Test
    void isLocked_whenAccountLocked_isTrue() {
        final LoginThrottles throttles = throttles();
        failAccountToLock(throttles, "a@example.com", IP);

        assertThat(throttles.isLocked("a@example.com", IP, NOW))
                .as("A locked account must block, even below the IP limit")
                .isTrue();
    }

    @Test
    void isLocked_whenOnlyIpLocked_isTrue() {
        final LoginThrottles throttles = throttles();
        failIpToLock(throttles, IP);

        assertThat(throttles.isLocked("brand-new@example.com", IP, NOW))
                .as("A locked IP must block even a never-seen account")
                .isTrue();
    }

    @Test
    void isLocked_whenNeitherLocked_isFalse() {
        final LoginThrottles throttles = throttles();
        throttles.recordFailure("a@example.com", IP, NOW);

        assertThat(throttles.isLocked("a@example.com", IP, NOW))
                .as("A single failure locks neither dimension")
                .isFalse();
    }

    @Test
    void recordFailure_reportsBothDimensions() {
        final LoginThrottles throttles = throttles();

        final LoginThrottles.ThrottleOutcome outcome = throttles.recordFailure("a@example.com", IP, NOW);

        assertThat(outcome.account().maxAttempts())
                .as("The account outcome must carry the account limit")
                .isEqualTo(ACCOUNT_MAX);
        assertThat(outcome.ip().maxAttempts())
                .as("The IP outcome must carry the IP limit")
                .isEqualTo(IP_MAX);
    }

    @Test
    void recordSuccess_clearsAccountButNotIp() {
        final LoginThrottles throttles = throttles();
        throttles.recordFailure("a@example.com", IP, NOW);   // account=1, ip=1

        throttles.recordSuccess("a@example.com");

        final LoginThrottles.ThrottleOutcome outcome = throttles.recordFailure("a@example.com", IP, NOW);
        assertThat(outcome.account().failureCount())
                .as("Success must reset the account counter")
                .isEqualTo(1);
        assertThat(outcome.ip().failureCount())
                .as("Success must NOT reset the IP counter — a valid login can't reset an IP's budget")
                .isEqualTo(2);
    }

    @Test
    void lockoutRemaining_whenOnlyAccountLocked_isAccountWindow() {
        final LoginThrottles throttles = throttles();
        failAccountToLock(throttles, "a@example.com", IP);

        assertThat(throttles.lockoutRemaining("a@example.com", IP, NOW))
                .as("With only the account locked, the wait is the account window")
                .isEqualTo(ACCOUNT_LOCKOUT);
    }

    @Test
    void lockoutRemaining_whenOnlyIpLocked_isIpWindow() {
        final LoginThrottles throttles = throttles();
        failIpToLock(throttles, IP);

        assertThat(throttles.lockoutRemaining("brand-new@example.com", IP, NOW))
                .as("With only the IP locked, the wait is the (longer) IP window")
                .isEqualTo(IP_LOCKOUT);
    }

    @Test
    void lockoutRemaining_whenNeitherLocked_isZero() {
        assertThat(throttles().lockoutRemaining("a@example.com", IP, NOW))
                .as("No lockout means no wait")
                .isEqualTo(Duration.ZERO);
    }

    private record FixedThrottleConfig(boolean enabled, int maxAttempts, Duration lockoutDuration) implements ThrottleConfig {
    }

    private record FixedIpThrottleConfig(boolean enabled, int maxAttempts, Duration lockoutDuration) implements IpThrottleConfig {
    }
}
