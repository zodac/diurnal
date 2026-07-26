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

import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.user.UserSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of account registration — the shared per-IP lockout check, field validation, duplicate-email check and user creation — used by
 * both the web form ({@code WebResource.register}) and the REST API ({@code AuthResource.register}), so a rule added or changed here applies to both
 * surfaces by construction (the {@link AuthenticationService} pattern). The resources only translate the returned {@link RegistrationResult} into
 * their medium and keep their deliberately different <em>enablement policies</em> (the web setup flow may create the very first — administrator —
 * account; the API never may).
 *
 * <p>
 * The unified validation rules: every field is required; the email must contain an {@code @}; the display name must be 2–100 characters after
 * stripping; the password is capped at {@link PasswordConstraints#MAX_LENGTH}. When the caller collects a confirmation password (the web form), it
 * must match. Every rejected submission is recorded against the shared per-IP throttle ({@link IpThrottle}) exactly once.
 *
 * <p>
 * Transaction discipline: the deliberately expensive Argon2id hash is computed OUTSIDE any database transaction, so no pooled connection sits idle
 * for the duration of the hash. Only the account insert runs in the short service-owned transaction of {@link #createUser(String, String, String)} —
 * callers must NOT be {@code @Transactional}, or the hashing would run inside their transaction again. The session a resource mints from a
 * {@code Success} is a separate (store-owned) transaction; a crash in between leaves a valid account with no session, which simply logs in normally.
 */
@ApplicationScoped
public class RegistrationService {

    private static final Logger LOGGER = LogManager.getLogger(RegistrationService.class);

    private final Instance<RegistrationService> self;
    private final Passwords passwords;
    private final RoleAssigner roleAssigner;
    private final IpThrottle ipThrottle;

    /**
     * Injects collaborators and a lazy self-reference. The self {@link Instance} resolves the CDI client proxy on demand so the short
     * {@code @Transactional} {@link #createUser(String, String, String)} runs through the proxy (applying the interceptor) without a
     * construction-time cycle.
     *
     * @param self a lazy self-reference used to invoke the transactional {@code createUser} through the CDI proxy
     * @param passwords the Argon2id password service
     * @param roleAssigner the shared role-assignment policy
     * @param ipThrottle the per-IP registration throttle
     */
    @Inject
    public RegistrationService(final Instance<RegistrationService> self, final Passwords passwords, final RoleAssigner roleAssigner,
        final IpThrottle ipThrottle) {
        this.self = self;
        this.passwords = passwords;
        this.roleAssigner = roleAssigner;
        this.ipThrottle = ipThrottle;
    }

    /**
     * Validates a registration submission and creates the account.
     *
     * @param email           the submitted email ({@code null} is treated as blank)
     * @param displayName     the submitted display name ({@code null} is treated as blank)
     * @param password        the submitted password ({@code null} is treated as blank)
     * @param confirmPassword the re-entered password when the surface collects one (the web form); {@code null} when it does not (the API, where
     *                        confirmation is the client's concern)
     * @param clientIp        the client IP, for the shared per-IP lockout
     * @param now             the current instant (from {@code AppClock}), for the lockout window
     * @return the outcome
     */
    public RegistrationResult register(final @Nullable String email, final @Nullable String displayName, final @Nullable String password,
        final @Nullable String confirmPassword, final String clientIp, final Instant now) {

        // The shared per-IP lockout (the same counter failed logins feed). Revealed on the first attempt
        // made WHILE locked — this entry check — not on the threshold-tripping one below.
        if (ipThrottle.isLocked(clientIp, now)) {
            LOGGER.debug("Throttled registration attempt (IP: {})", clientIp);
            return new RegistrationResult.LockedOut(ipThrottle.lockoutRemaining(clientIp, now));
        }

        final String emailValue = email == null ? "" : email;
        final String displayNameValue = displayName == null ? "" : displayName;
        final String passwordValue = password == null ? "" : password;

        final List<String> missingFields = missingFields(emailValue, displayNameValue, passwordValue, confirmPassword);
        final List<String> errors = validate(emailValue, displayNameValue, passwordValue, confirmPassword);
        if (!missingFields.isEmpty() || !errors.isEmpty()) {
            RegistrationAttemptLog.logFailure(LOGGER, ipThrottle.recordFailure(clientIp, now), emailValue, clientIp);
            return new RegistrationResult.Invalid(missingFields, errors);
        }

        final String normalised = emailValue.toLowerCase(Locale.ROOT).strip();
        if (User.findByEmail(normalised).isPresent()) {
            RegistrationAttemptLog.logFailure(LOGGER, ipThrottle.recordFailure(clientIp, now), emailValue, clientIp);
            return new RegistrationResult.DuplicateEmail();
        }

        // The Argon2id hash is computed here, outside any transaction (see the class Javadoc).
        final String passwordHash = passwords.hash(passwordValue);
        final RegistrationResult result = self.get().createUser(normalised, displayNameValue.strip(), passwordHash);
        if (result instanceof RegistrationResult.DuplicateEmail) {
            // A concurrent registration won the race for this email after the pre-check passed; record it against the shared per-IP throttle
            // exactly as a duplicate caught by the pre-check above would be.
            RegistrationAttemptLog.logFailure(LOGGER, ipThrottle.recordFailure(clientIp, now), emailValue, clientIp);
        }
        return result;
    }

    /**
     * Creates the already-validated account in one short transaction. Invoked via the CDI proxy ({@code self}) so the {@code @Transactional}
     * interceptor applies; the password hash has already been computed by {@link #register}, outside the transaction.
     *
     * @param email        the normalised (lower-cased, stripped) email
     * @param displayName  the stripped display name
     * @param passwordHash the Argon2id hash to store
     * @return {@link RegistrationResult.Success} with the new account, or {@link RegistrationResult.DuplicateEmail} when a concurrent registration
     *     won the race for the email
     */
    @Transactional
    RegistrationResult createUser(final String email, final String displayName, final String passwordHash) {
        final User user = new User();
        user.email = email;
        user.displayName = displayName;
        user.passwordHash = passwordHash;
        user.role = roleAssigner.roleForNewUser();
        // Registration logs the account straight in on both surfaces (a session is minted from the
        // result), so the first login is now.
        user.lastLoginAt = Instant.now();
        user.persist();

        // The duplicate-email pre-check in register() is a TOCTOU: two concurrent registrations of the same email can both pass it, and the loser
        // would otherwise surface the users_email_unique violation as a 500 at commit. Flushing here forces the INSERT now, turning that race into a
        // DuplicateEmail result; the failed flush marks this transaction rollback-only, so nothing is persisted (Quarkus rolls back cleanly).
        try {
            Panache.getEntityManager().flush();
        } catch (final ConstraintViolationException e) {
            LOGGER.debug("Concurrent duplicate registration for email: {}", email, e);
            return new RegistrationResult.DuplicateEmail();
        }

        LOGGER.info("New user registered: {} (role={})", email, user.role);
        return new RegistrationResult.Success(user);
    }

    private static List<String> missingFields(final String email, final String displayName, final String password,
        final @Nullable String confirmPassword) {
        final List<String> missing = new ArrayList<>();
        if (email.isBlank()) {
            missing.add("Email");
        }
        if (displayName.isBlank()) {
            missing.add("Display name");
        }
        if (password.isEmpty()) {
            missing.add("Password");
        }
        if (confirmPassword != null && confirmPassword.isEmpty()) {
            missing.add("Confirm password");
        }
        return missing;
    }

    private static List<String> validate(final String email, final String displayName, final String password,
        final @Nullable String confirmPassword) {
        final List<String> errors = new ArrayList<>();

        if (!email.isBlank() && !email.contains("@")) {
            errors.add("Email must contain an @ symbol.");
        }

        if (!displayName.isBlank() && UserSettings.isInvalidDisplayName(displayName.strip())) {
            errors.add(UserSettings.DISPLAY_NAME_RANGE_MESSAGE);
        }

        if (password.length() > PasswordConstraints.MAX_LENGTH) {
            errors.add("Password must be at most " + PasswordConstraints.MAX_LENGTH + " characters.");
        }

        if (confirmPassword != null && !password.isEmpty() && !confirmPassword.isEmpty() && !password.equals(confirmPassword)) {
            errors.add("The passwords did not match.");
        }

        return errors;
    }
}
