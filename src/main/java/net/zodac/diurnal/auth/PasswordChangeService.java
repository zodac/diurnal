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
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of a user changing their own password — the local-account guard, the current-password proof, the new-password rules, the Argon2id
 * re-hash and the "sign out every other device" revocation — shared by the Settings page ({@code WebResource}) and the REST API's
 * {@code PUT /api/v1/users/me/password} ({@code UserResource}), so a rule added or changed here applies to both surfaces by construction (the
 * {@link AuthenticationService} pattern). The resources only translate the returned {@link PasswordChangeResult} into their medium.
 *
 * <p>
 * The caller must prove knowledge of the existing password — this is what stops a hijacked session from silently taking over the account. There is
 * deliberately <b>no</b> lockout on failures here: an already-authenticated user changing their OWN password gets unlimited attempts, wholly separate
 * from the per-IP login/registration lockout ({@link IpThrottle}) — a mismatch never consults nor feeds that shared counter, so failed password
 * changes can never lock the IP out of logging in or registering, and vice versa. The {@code WARN} on a mismatch is an audit trail only.
 *
 * <p>
 * Callers own the transaction (each endpoint is {@code @Transactional}); this bean only assumes one is active.
 */
@ApplicationScoped
public class PasswordChangeService {

    /**
     * User-facing rejection message when the submitted current password does not match the stored hash.
     */
    public static final String CURRENT_PASSWORD_ERROR = "Current password is incorrect";

    /**
     * User-facing rejection message when the new password is empty or the re-entered copy does not match.
     */
    public static final String NEW_PASSWORD_ERROR = "Passwords do not match";

    /**
     * User-facing rejection message when the new password exceeds {@link PasswordConstraints#MAX_LENGTH} characters.
     */
    public static final String NEW_PASSWORD_TOO_LONG_ERROR =
        "Password must be at most " + PasswordConstraints.MAX_LENGTH + " characters";

    private static final Logger LOGGER = LogManager.getLogger(PasswordChangeService.class);

    @Inject
    Passwords passwords;

    @Inject
    SessionStore sessionStore;

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    /**
     * Verifies the current password without changing anything (the Settings page confirms step 1 of its flow with this before asking for the new
     * password). A UX aid only — {@link #change} re-verifies authoritatively on the mutating request.
     *
     * @param user            the acting user
     * @param currentPassword the password to check against the stored hash
     * @param clientIp        the client IP, for the audit log
     * @return the outcome
     */
    public PasswordChangeResult verify(final User user, final @Nullable String currentPassword, final String clientIp) {
        if (notLocalAccount(user)) {
            return new PasswordChangeResult.NotLocalAccount();
        }
        if (currentPasswordMismatch(user, currentPassword)) {
            LOGGER.warn("Failed current-password check on password-change verify for user: {} (IP: {})", user.email, clientIp);
            return new PasswordChangeResult.WrongCurrentPassword();
        }
        return new PasswordChangeResult.Success();
    }

    /**
     * Changes the password after proving knowledge of the current one, then revokes every <em>other</em> session (a common response to suspected
     * compromise) while keeping the calling session signed in.
     *
     * @param user            the acting user
     * @param currentPassword the existing password, as proof of ownership
     * @param newPassword     the new password
     * @param confirmPassword the re-entered new password when the surface collects one (the web form); {@code null} when it does not (the API,
     *                        where confirmation is the client's concern)
     * @param currentRawToken the session token making this request, spared from the revocation; {@code null} revokes nothing
     * @param clientIp        the client IP, for the audit log
     * @return the outcome
     */
    public PasswordChangeResult change(final User user, final @Nullable String currentPassword, final @Nullable String newPassword,
        final @Nullable String confirmPassword, final @Nullable String currentRawToken, final String clientIp) {
        if (notLocalAccount(user)) {
            return new PasswordChangeResult.NotLocalAccount();
        }
        // The current-password proof is checked before the new-password rules, so a wrong current
        // password is reported (and the web client sent back to its first step) regardless of the rest.
        if (currentPasswordMismatch(user, currentPassword)) {
            LOGGER.warn("Failed current-password check on password change for user: {} (IP: {})", user.email, clientIp);
            return new PasswordChangeResult.WrongCurrentPassword();
        }
        if (newPassword == null || newPassword.isEmpty() || (confirmPassword != null && !newPassword.equals(confirmPassword))) {
            return new PasswordChangeResult.InvalidNewPassword(NEW_PASSWORD_ERROR);
        }
        // Cap the length to bound the hashing cost (an over-long input is a cheap CPU-exhaustion lever) —
        // mirrors the registration cap so the new password is bounded identically however it arrives.
        if (newPassword.length() > PasswordConstraints.MAX_LENGTH) {
            return new PasswordChangeResult.InvalidNewPassword(NEW_PASSWORD_TOO_LONG_ERROR);
        }

        user.passwordHash = passwords.hash(newPassword);
        user.persist();
        if (currentRawToken != null && !currentRawToken.isBlank()) {
            sessionStore.revokeOthersForUser(user.id, currentRawToken);
        }
        LOGGER.info("Password changed for user: {} (other sessions revoked)", user.email);
        return new PasswordChangeResult.Success();
    }

    // Only local accounts have a password to verify or change; OIDC-only users (and deployments with
    // password auth switched off entirely) have none.
    private boolean notLocalAccount(final User user) {
        return !passwordAuthConfig.enabled() || user.passwordHash == null || user.passwordHash.isBlank();
    }

    private boolean currentPasswordMismatch(final User user, final @Nullable String currentPassword) {
        // The notLocalAccount guard has already established a non-blank stored hash.
        return currentPassword == null || currentPassword.isEmpty()
                || !passwords.matches(currentPassword, java.util.Objects.requireNonNull(user.passwordHash));
    }
}
