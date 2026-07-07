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

import org.apache.logging.log4j.Logger;

/**
 * Shared failure logging for both login surfaces ({@link AuthResource}, {@link PasswordIdentityProvider}),
 * so the API and the web form emit identical lines. The caller passes its own {@link Logger} so the log
 * still names the originating class.
 */
final class LoginAttemptLog {

    private LoginAttemptLog() {

    }

    /**
     * Logs a failed login: the per-account running count at {@code DEBUG}, and a {@code WARN} for each
     * dimension (account and/or IP) that this failure locked out.
     *
     * @param logger   the caller's logger (so the source class is named)
     * @param outcome  the combined per-account and per-IP outcome
     * @param email    the submitted email
     * @param clientIp the client IP
     */
    static void logFailure(final Logger logger, final LoginThrottles.ThrottleOutcome outcome,
            final String email, final String clientIp) {
        final LoginThrottle.FailureOutcome account = outcome.account();
        if (account.failureCount() >= 1) {
            logger.debug("Failed login attempt ({} of {}) for: {}", account.failureCount(), account.maxAttempts(), email);
        } else {
            logger.debug("Failed login attempt for: {}", email);
        }
        if (account.lockedOut()) {
            logger.warn("Account locked out: {} for {} after {} failed attempts (IP: {})",
                    email, LockoutMessages.humanReadable(account.lockoutDuration()), account.maxAttempts(), clientIp);
        }
        final LoginThrottle.FailureOutcome ip = outcome.ip();
        if (ip.lockedOut()) {
            logger.warn("IP locked out: {} for {} after {} failed attempts (last account: {})",
                    clientIp, LockoutMessages.humanReadable(ip.lockoutDuration()), ip.maxAttempts(), email);
        }
    }
}
