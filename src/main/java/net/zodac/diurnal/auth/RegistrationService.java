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
import net.zodac.diurnal.auth.lockout.IpLockoutService;
import net.zodac.diurnal.auth.lockout.IpThrottle;
import net.zodac.diurnal.http.NotUiFacing;
import net.zodac.diurnal.note.NoteKeys;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextOutcomeExtensions;
import net.zodac.diurnal.text.TextValidation;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of account registration — the shared per-IP lockout check, field validation, duplicate-email check and user creation — used by
 * both the web form ({@code AuthWebResource.register}) and the REST API ({@code AuthResource.register}), so a rule added or changed here applies
 * to both surfaces by construction (the {@link AuthenticationService} pattern). The resources only translate the returned
 * {@link RegistrationResult} into their medium and keep their deliberately different <em>enablement policies</em> (the web setup flow may create
 * the very first — administrator — account; the API never may).
 *
 * <p>
 * The unified validation rules: every field is required, and the email, display name and password are each checked against their entry in the
 * shared {@code TextFields} catalogue - so registration cannot accept a value the Settings page would reject, or vice versa. When the caller collects
 * a confirmation password (the web form), it must match. Every rejected submission is recorded against the shared per-IP throttle
 * ({@link IpThrottle}) exactly once.
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
    private final IpLockoutService ipLockoutService;
    private final NoteKeys noteKeys;

    /**
     * Injects collaborators and a lazy self-reference. The self {@link Instance} resolves the CDI client proxy on demand so the short
     * {@code @Transactional} {@link #createUser(String, String, String)} runs through the proxy (applying the interceptor) without a
     * construction-time cycle.
     *
     * @param self a lazy self-reference used to invoke the transactional {@code createUser} through the CDI proxy
     * @param passwords the Argon2id password service
     * @param roleAssigner the shared role-assignment policy
     * @param ipThrottle the per-IP registration throttle
     * @param ipLockoutService the shared per-IP lockout recorder (records the failure and persists a history row when a lockout trips)
     * @param noteKeys the notes key service, which mints the new account's data key
     */
    @Inject
    public RegistrationService(final Instance<RegistrationService> self, final Passwords passwords, final RoleAssigner roleAssigner,
        final IpThrottle ipThrottle, final IpLockoutService ipLockoutService, final NoteKeys noteKeys) {
        this.self = self;
        this.passwords = passwords;
        this.roleAssigner = roleAssigner;
        this.ipThrottle = ipThrottle;
        this.ipLockoutService = ipLockoutService;
        this.noteKeys = noteKeys;
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

        // Case-folded BEFORE the pipeline, not after: lower-casing can LENGTHEN a value (a Turkish dotted capital I becomes two code points), so an
        // address folded afterwards could exceed the bound it was just checked against - and the column - turning a 400 into a 500 at INSERT time.
        // Folding first also keeps the rule that the value checked is exactly the value stored.
        final String emailValue = email == null ? "" : email.toLowerCase(Locale.ROOT);
        final String displayNameValue = displayName == null ? "" : displayName;
        final String passwordValue = password == null ? "" : password;

        // Each field is put through the pipeline EXACTLY ONCE; the outcomes carry both the verdict and the normalised value, so nothing below
        // re-checks or re-normalises a submission.
        final TextOutcome emailOutcome = TextValidation.check(TextFields.EMAIL, emailValue);
        final TextOutcome displayNameOutcome = TextValidation.check(TextFields.DISPLAY_NAME, displayNameValue);
        final TextOutcome passwordOutcome = TextValidation.check(TextFields.PASSWORD, passwordValue);

        final List<RegistrationResult.RequiredField> missingFields =
            missingFields(emailOutcome, displayNameOutcome, passwordOutcome, confirmPassword);
        final List<RegistrationError> errors =
            errors(emailOutcome, displayNameOutcome, passwordOutcome, passwordValue, confirmPassword);
        if (!missingFields.isEmpty() || !errors.isEmpty()) {
            RegistrationAttemptLog.logFailure(LOGGER, ipLockoutService.recordFailure(clientIp, now), emailValue, clientIp);
            return new RegistrationResult.Invalid(missingFields, errors);
        }

        // Already lower-cased above, for the case-insensitive uniqueness the column relies on; this is the value the check produced, unaltered.
        final String normalised = acceptedValue(emailOutcome, emailValue);
        if (User.findByEmail(normalised).isPresent()) {
            RegistrationAttemptLog.logFailure(LOGGER, ipLockoutService.recordFailure(clientIp, now), emailValue, clientIp);
            return new RegistrationResult.DuplicateEmail();
        }

        // The Argon2id hash is computed here, outside any transaction (see the class Javadoc).
        final String passwordHash = passwords.hash(passwordValue);
        final RegistrationResult result = self.get().createUser(normalised, acceptedValue(displayNameOutcome, displayNameValue), passwordHash);
        if (result instanceof RegistrationResult.DuplicateEmail) {
            // A concurrent registration won the race for this email after the pre-check passed; record it against the shared per-IP throttle
            // exactly as a duplicate caught by the pre-check above would be.
            RegistrationAttemptLog.logFailure(LOGGER, ipLockoutService.recordFailure(clientIp, now), emailValue, clientIp);
        }
        return result;
    }

    /**
     * Creates the already-validated account in one short transaction. Invoked via the CDI proxy ({@code self}) so the {@code @Transactional}
     * interceptor applies; the password hash has already been computed by {@link #register}, outside the transaction.
     *
     * @param email        the normalised (lower-cased, stripped) email
     * @param displayName  the normalised display name
     * @param passwordHash the Argon2id hash to store
     * @return {@link RegistrationResult.Success} with the new account, or {@link RegistrationResult.DuplicateEmail} when a concurrent registration
     *     won the race for the email
     */
    // Package-private is REQUIRED, not incidental: this runs through the CDI proxy so the @Transactional interceptor applies, and an
    // interceptor is never applied to a private method - narrowing it would silently drop the transaction while still compiling.
    @SuppressWarnings("WeakerAccess")
    @Transactional
    RegistrationResult createUser(final String email, final String displayName, final String passwordHash) {
        final User user = new User();
        user.email = email;
        user.displayName = displayName;
        user.passwordHash = passwordHash;
        user.role = roleAssigner.roleForNewUser();
        // Registration logs the account straight in on both surfaces (a session is minted from the result), so the first login is now.
        user.lastLoginAt = Instant.now();
        user.persist();

        // Every account gets its notes data key at creation, so no part of the application ever has to treat
        // "user without a key" as a live state. Invisible to the user - see NoteKeys.
        noteKeys.assignTo(user.id);

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

    private static List<RegistrationResult.RequiredField> missingFields(final TextOutcome email, final TextOutcome displayName,
        final TextOutcome password, final @Nullable String confirmPassword) {
        final List<RegistrationResult.RequiredField> missing = new ArrayList<>();
        addIfBlank(missing, email, RegistrationResult.RequiredField.EMAIL);
        addIfBlank(missing, displayName, RegistrationResult.RequiredField.DISPLAY_NAME);
        addIfBlank(missing, password, RegistrationResult.RequiredField.PASSWORD);
        // Surface policy: the confirmation is a web-form-only field with no stored value, so it has no catalogue entry of its own.
        if (confirmPassword != null && confirmPassword.isEmpty()) {
            missing.add(RegistrationResult.RequiredField.CONFIRM_PASSWORD);
        }
        return missing;
    }

    private static void addIfBlank(final List<RegistrationResult.RequiredField> missing, final TextOutcome outcome,
        final RegistrationResult.RequiredField field) {
        if (outcome instanceof TextOutcome.Blank) {
            missing.add(field);
        }
    }

    private static List<RegistrationError> errors(final TextOutcome email, final TextOutcome displayName,
        final TextOutcome password, final String passwordValue, final @Nullable String confirmPassword) {
        final List<RegistrationError> errors = new ArrayList<>();
        addRejection(errors, email);
        addRejection(errors, displayName);
        addRejection(errors, password);

        if (confirmPassword != null && !passwordValue.isEmpty() && !confirmPassword.isEmpty() && !passwordValue.equals(confirmPassword)) {
            errors.add(new RegistrationError.PasswordMismatch());
        }

        return errors;
    }

    // A blank value is already listed by missingFields(), so only a real length/content problem is worded as an error here.
    private static void addRejection(final List<RegistrationError> errors, final TextOutcome outcome) {
        if (outcome instanceof final TextOutcome.Failure failure && !(failure instanceof TextOutcome.Blank)) {
            errors.add(new RegistrationError.FieldError(failure));
        }
    }

    /**
     * The English wording for a required field left blank, for the API's {@code 400} body. The web surface instead resolves a translated label from
     * the field's {@link RegistrationResult.RequiredField#key()} in {@code register.html}.
     *
     * @param field the blank field
     * @return the default (English) label
     */
    @NotUiFacing(reason = "names the blank fields in the /api/v1 400 body; the form's own banner is msg:missingFieldsIntro")
    public static String label(final RegistrationResult.RequiredField field) {
        return switch (field) {
            case EMAIL -> "Email";
            case DISPLAY_NAME -> "Display name";
            case PASSWORD -> "Password";
            case CONFIRM_PASSWORD -> "Confirm password";
        };
    }

    /**
     * The English wording for a rejected (present but invalid) registration field, for the API's {@code 400} body. The web surface instead resolves
     * a translated sentence via {@code partials/text-failure-message.html}/{@code partials/password-rejection.html}.
     *
     * @param error the rejection cause
     * @return the default (English) message
     */
    @NotUiFacing(reason = "the /api/v1 400 body; the register form renders partials/text-failure-message.html and its own bundle entries")
    public static String message(final RegistrationError error) {
        return switch (error) {
            case final RegistrationError.FieldError fieldError -> TextOutcomeExtensions.message(fieldError.failure());
            case final RegistrationError.PasswordMismatch _ -> "Passwords do not match";
        };
    }

    // The normalised value the single pipeline pass produced. The fallback is unreachable from register() (every field is known to be Valid by the
    // time this is called) and exists only so the accepted value never has to be recomputed from the raw submission.
    private static String acceptedValue(final TextOutcome outcome, final String submitted) {
        return outcome instanceof TextOutcome.Valid(final String value) ? value : submitted;
    }
}
