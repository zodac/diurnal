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
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Verifies email/password credentials for both login surfaces (the web form and the REST API), applying login throttling and recording the outcome.
 * It performs only the credential check — minting the actual session is the caller's job — so the same throttle + Argon2id path is shared and cannot
 * drift between the two surfaces.
 *
 * <p>
 * Transaction discipline: the deliberately expensive Argon2id work (verification, and the transparent cost-upgrade re-hash) runs OUTSIDE any
 * database transaction, so no pooled connection sits idle for the duration of a hash. Only the success bookkeeping ({@code lastLoginAt} and an
 * upgraded hash) is written, in the short service-owned transactions of {@link #recordLogin(UUID)} / {@link #recordLoginWithRehash(UUID, String)} —
 * callers must NOT be {@code @Transactional}, or the hashing would run inside their transaction again.
 */
@ApplicationScoped
public class AuthenticationService {

    private static final Logger LOGGER = LogManager.getLogger(AuthenticationService.class);

    private final Instance<AuthenticationService> self;
    private final IpThrottle ipThrottle;
    private final IpLockoutService ipLockoutService;
    private final PasswordAuthConfig passwordAuthConfig;
    private final Passwords passwords;

    /**
     * Injects collaborators and a lazy self-reference. The self {@link Instance} resolves the CDI client proxy on demand so the short
     * {@code @Transactional} {@code recordLogin} methods are invoked through the proxy (applying the interceptor) without a construction-time cycle.
     *
     * @param self a lazy self-reference used to invoke the transactional {@code recordLogin} methods through the CDI proxy
     * @param ipThrottle the per-IP login throttle
     * @param ipLockoutService the shared per-IP lockout recorder (records the failure and persists a history row when a lockout trips)
     * @param passwordAuthConfig the password-auth settings
     * @param passwords the Argon2id password service
     */
    @Inject
    public AuthenticationService(final Instance<AuthenticationService> self, final IpThrottle ipThrottle,
        final IpLockoutService ipLockoutService, final PasswordAuthConfig passwordAuthConfig, final Passwords passwords) {
        this.self = self;
        this.ipThrottle = ipThrottle;
        this.ipLockoutService = ipLockoutService;
        this.passwordAuthConfig = passwordAuthConfig;
        this.passwords = passwords;
    }

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
        // The filter above has already established a non-null stored hash.
        // No stored hash to verify against. Spend the same time as a real check so a non-existent
        // account cannot be told apart from a wrong password by response time (user enumeration).
        credentialsValid = account
            .map(user -> passwords.matches(password, Objects.requireNonNull(user.passwordHash)))
            .orElseGet(() -> passwordAuthConfig.uniformTimingEnabled() && passwords.matchesDummy(password));

        if (credentialsValid) {
            final User user = account.orElseThrow();
            // Transparently upgrade a hash made under weaker Argon2id parameters to the current cost now
            // that we hold the verified plaintext. The re-hash is computed here, outside the transaction.
            final String storedHash = Objects.requireNonNull(user.passwordHash);
            if (passwords.needsRehash(storedHash)) {
                return self.get().recordLoginWithRehash(user.id, passwords.hash(password));
            }
            return self.get().recordLogin(user.id);
        }

        final AttemptThrottle.FailureOutcome outcome = ipLockoutService.recordFailure(clientIp, now);
        LoginAttemptLog.logFailure(LOGGER, outcome, email, clientIp);
        return new LoginResult.InvalidCredentials();
    }

    /**
     * Records a verified login in one short transaction, bumping {@code lastLoginAt}. Use this overload when the stored hash is already at the
     * current Argon2id cost and needs no upgrade; {@link #recordLoginWithRehash(UUID, String)} is the counterpart that also persists a re-hash.
     * Invoked via the CDI proxy ({@code self}) so the {@code @Transactional} interceptor applies; the account is re-read by primary key inside the
     * transaction so the write targets a managed entity.
     *
     * <p>
     * Note: a success deliberately does NOT clear the IP counter - a valid login must not reset an IP's brute-force budget (see {@link IpThrottle});
     * it decays on its own after a quiet window.
     *
     * @param userId the verified account's ID
     * @return the {@link LoginResult}: success, or invalid credentials when the account vanished since verification
     */
    @Transactional
    LoginResult recordLogin(final UUID userId) {
        final User user = User.findById(userId);
        if (user == null) {
            // The account was deleted between the credential check and this write.
            return new LoginResult.InvalidCredentials();
        }

        return finaliseLogin(user);
    }

    /**
     * Records a verified login in one short transaction, persisting the already-computed {@code upgradedHash} (re-hashed at the current Argon2id cost
     * outside any transaction) and bumping {@code lastLoginAt}. Use this overload when {@link Passwords#needsRehash(String)} flagged the stored hash
     * as outdated; {@link #recordLogin(UUID)} is the counterpart when no upgrade is due. Invoked via the CDI proxy ({@code self}) so the
     * {@code @Transactional} interceptor applies; the account is re-read by primary key inside the transaction so the write targets a managed entity.
     *
     * @param userId the verified account's ID
     * @param upgradedHash the Argon2id hash re-computed at the current cost
     * @return the {@link LoginResult}: success, or invalid credentials when the account vanished since verification
     */
    @Transactional
    LoginResult recordLoginWithRehash(final UUID userId, final String upgradedHash) {
        final User user = User.findById(userId);
        if (user == null) {
            // The account was deleted between the credential check and this write.
            return new LoginResult.InvalidCredentials();
        }

        LOGGER.debug("Upgraded password hash to current Argon2id cost for user {}", user.email);
        user.passwordHash = upgradedHash;
        return finaliseLogin(user);
    }

    /**
     * Shared tail of both {@code recordLogin} overloads: stamps {@code lastLoginAt} on the already-loaded managed account and persists it.
     *
     * @param user the managed account being logged in
     * @return the {@link LoginResult.Success} for the login
     */
    private static LoginResult finaliseLogin(final User user) {
        user.lastLoginAt = Instant.now();
        user.persist();
        LOGGER.debug("Successful login: name={} email={} role={}", user.displayName, user.email, user.role);
        return new LoginResult.Success(user);
    }
}
