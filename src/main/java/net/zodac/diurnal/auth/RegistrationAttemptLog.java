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
 * Shared failure logging for both registration surfaces (the web form and {@code POST /api/auth/register}), so they emit identical lines. The caller
 * passes its own {@link Logger} so the log still names the originating class. Mirrors {@link LoginAttemptLog}.
 */
public final class RegistrationAttemptLog {

    private RegistrationAttemptLog() {

    }

    /**
     * Logs a rejected registration: the per-IP running count at {@code DEBUG}, and a {@code WARN} when this failure locked the IP out.
     *
     * @param logger the caller's logger (so the source class is named)
     * @param outcome the per-IP throttle outcome for this failure
     * @param email the submitted email
     * @param clientIp the client IP
     */
    public static void logFailure(final Logger logger, final AttemptThrottle.FailureOutcome outcome,
        final String email, final String clientIp) {
        if (outcome.failureCount() >= 1) {
            logger.debug("Failed registration attempt ({} of {}) for: {} (IP: {})",
                outcome.failureCount(), outcome.maxAttempts(), email, clientIp);
        } else {
            logger.debug("Failed registration attempt for: {} (IP: {})", email, clientIp);
        }
        if (outcome.lockedOut()) {
            logger.warn("Registration IP locked out: {} for {} after {} failed attempts (last email: {})",
                clientIp, LockoutMessages.humanReadable(outcome.lockoutDuration()), outcome.maxAttempts(), email);
        }
    }
}
