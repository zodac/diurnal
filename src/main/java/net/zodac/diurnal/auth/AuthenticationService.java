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
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verifies email/password credentials for both login surfaces (the web form and the REST API), applying login throttling and recording the outcome.
 * It performs only the credential check — minting the actual session is the caller's job — so the same throttle + Argon2id path is shared and cannot
 * drift between the two surfaces.
 */
@ApplicationScoped
public class AuthenticationService {

    private static final Logger LOGGER = LogManager.getLogger(AuthenticationService.class);

    @Inject
    IpThrottle ipThrottle;

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @Inject
    Passwords passwords;

    /**
     * Checks the given credentials against the account store at {@code now}. Enforces the global per-IP lockout first, then verifies the Argon2id
     * hash; on success the account's {@code lastLoginAt} is updated. A failure feeds the shared IP counter (the same one registration failures feed).
     *
     * @param rawEmail the submitted email (any case/whitespace; normalised internally)
     * @param password the submitted password
     * @param clientIp the requesting client's IP, for throttling and security logging
     * @param now the current instant (from {@code AppClock})
     * @return a {@link LoginResult} describing success, invalid credentials, or a lockout
     */
    @Transactional
    public LoginResult authenticate(final String rawEmail, final String password, final String clientIp, final Instant now) {
        final String email = rawEmail.toLowerCase(Locale.ROOT).strip();

        if (!passwordAuthConfig.enabled()) {
            return new LoginResult.InvalidCredentials();
        }

        if (ipThrottle.isLocked(clientIp, now)) {
            LOGGER.debug("Throttled login attempt for: {} (IP: {})", email, clientIp);
            return new LoginResult.LockedOut(ipThrottle.lockoutRemaining(clientIp, now));
        }

        final Optional<User> account = User.findByEmail(email)
            .filter(u -> u.passwordHash != null);

        final boolean credentialsValid;
        if (account.isPresent()) {
            // The filter above has already established a non-null stored hash.
            credentialsValid = passwords.matches(password, java.util.Objects.requireNonNull(account.get().passwordHash));
        } else {
            // No stored hash to verify against. Spend the same time as a real check so a non-existent
            // account cannot be told apart from a wrong password by response time (user enumeration).
            credentialsValid = passwordAuthConfig.uniformTimingEnabled() && passwords.matchesDummy(password);
        }

        if (credentialsValid) {
            final User user = account.orElseThrow();
            // Note: a success deliberately does NOT clear the IP counter — a valid login must not reset an
            // IP's brute-force budget (see IpThrottle); it decays on its own after a quiet window.
            // Transparently upgrade a hash made under weaker Argon2id parameters to the current cost now
            // that we hold the verified plaintext.
            if (passwords.needsRehash(java.util.Objects.requireNonNull(user.passwordHash))) {
                user.passwordHash = passwords.hash(password);
            }
            user.lastLoginAt = Instant.now();
            user.persist();
            LOGGER.debug("Successful login: name={} email={} role={}", user.displayName, user.email, user.role);
            return new LoginResult.Success(user);
        }

        final AttemptThrottle.FailureOutcome outcome = ipThrottle.recordFailure(clientIp, now);
        LoginAttemptLog.logFailure(LOGGER, outcome, email, clientIp);
        return new LoginResult.InvalidCredentials();
    }
}
