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
import java.util.UUID;
import net.zodac.diurnal.auth.lockout.IpThrottle;
import net.zodac.diurnal.auth.session.SessionStore;
import net.zodac.diurnal.http.NotUiFacing;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextOutcomeExtensions;
import net.zodac.diurnal.text.TextValidation;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The single owner of a user changing their own password — the local-account guard, the current-password proof, the new-password rules, the Argon2id
 * re-hash and the "sign out every other device" revocation — shared by the Settings page ({@code SettingsWebResource}) and the REST API's
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
 * Transaction discipline: the deliberately expensive Argon2id work (the current-password proof and the new hash) runs OUTSIDE any database
 * transaction, so no pooled connection sits idle for the duration of a hash. The hash write and the other-session revocation share the one short
 * service-owned transaction of {@link #applyChange(java.util.UUID, String, String)} — callers must NOT be {@code @Transactional}, or the hashing
 * would run inside their transaction again.
 */
@ApplicationScoped
public class PasswordChangeService {

    /**
     * User-facing rejection message when the submitted current password does not match the stored hash. What the API resource still calls
     * directly (it stays English by design, the same policy {@link #message(PasswordRejection)} documents); the web resource instead resolves a
     * translated sentence via {@code partials/password-rejection.html}'s {@code currentIncorrect} kind.
     */
    public static final String CURRENT_PASSWORD_ERROR = "Current password is incorrect";

    private static final Logger LOGGER = LogManager.getLogger(PasswordChangeService.class);

    private final Instance<PasswordChangeService> self;
    private final Passwords passwords;
    private final SessionStore sessionStore;

    /**
     * Injects collaborators and a lazy self-reference. The self {@link Instance} resolves the CDI client proxy on demand so the short
     * {@code @Transactional} {@code applyChange} runs through the proxy (applying the interceptor) without a construction-time cycle.
     *
     * @param self a lazy self-reference used to invoke the transactional {@code applyChange} through the CDI proxy
     * @param passwords the Argon2id password service
     * @param sessionStore the session store, used to revoke other sessions after a change
     */
    @Inject
    public PasswordChangeService(final Instance<PasswordChangeService> self, final Passwords passwords, final SessionStore sessionStore) {
        this.self = self;
        this.passwords = passwords;
        this.sessionStore = sessionStore;
    }

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
    // Coupled by the sealed result's own shape, not by a design smell: the current-password proof, the new-password
    // pipeline check and its three distinct rejection causes, and the unchanged-password guard are each one
    // necessary branch of the same rule, exactly as the constructors elsewhere in this file's package are
    // (@SuppressWarnings("OverlyCoupledMethod") on AuthWebResource/SettingsWebResource's DI constructors).
    @SuppressWarnings("OverlyCoupledMethod")
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
        // Surface policy: where a confirmation was collected, a mismatch is reported before anything else - the web form presents its two
        // new-password boxes as one "do these match" step, so the pair is wrong before either box's contents are.
        if (confirmPassword != null && !confirmPassword.equals(newPassword)) {
            return new PasswordChangeResult.InvalidNewPassword(new PasswordRejection.Mismatch());
        }
        // ONE pass through the same catalogue entry registration uses, so a new password is bounded identically however it arrives (the cap keeps
        // the hashing cost bounded - an over-long input is a cheap CPU-exhaustion lever). An absent password is a rejection of the same step as a
        // mismatch, so it carries that cause rather than the pipeline's blank one.
        final TextOutcome outcome = TextValidation.check(TextFields.PASSWORD, newPassword);
        if (!(outcome instanceof TextOutcome.Valid(final String value))) {
            return outcome instanceof TextOutcome.Blank
                ? new PasswordChangeResult.InvalidNewPassword(new PasswordRejection.Mismatch())
                : new PasswordChangeResult.InvalidNewPassword(
                    new PasswordRejection.TooLong((TextOutcome.Failure) outcome));
        }

        // A password change must actually change something: re-submitting the existing password would re-hash it and revoke every other session for
        // no gain, and silently accepting it tells the user their password changed when it did not. The current password has already been proven
        // above, so this is a plain comparison of two values known to be the account's - no second Argon2id verification.
        if (value.equals(currentPassword)) {
            return new PasswordChangeResult.InvalidNewPassword(new PasswordRejection.Unchanged());
        }

        // The new hash is computed here, outside any transaction (see the class Javadoc). It hashes the value the check above accepted - for a
        // secret that is the submission verbatim, but taking it from the outcome is what guarantees no second pass over the input.
        final String newHash = passwords.hash(value);
        return self.get().applyChange(user.id, newHash, currentRawToken);
    }

    /**
     * Persists the already-computed new hash and revokes every other session, in one short transaction. Invoked via the CDI proxy ({@code self}) so
     * the {@code @Transactional} interceptor applies; the account is re-read by primary key inside the transaction so the write targets a managed
     * entity.
     *
     * @param userId          the acting user's ID
     * @param newHash         the Argon2id hash of the new password, computed by {@link #change} outside the transaction
     * @param currentRawToken the session token making this request, spared from the revocation; {@code null} revokes nothing
     * @return the outcome: success, or {@link PasswordChangeResult.NotLocalAccount} when the account vanished since verification
     */
    @Transactional
    PasswordChangeResult applyChange(final UUID userId, final String newHash, final @Nullable String currentRawToken) {
        final User user = User.findById(userId);
        if (user == null) {
            // The account was deleted between the current-password proof and this write.
            return new PasswordChangeResult.NotLocalAccount();
        }
        user.passwordHash = newHash;
        user.persist();
        if (currentRawToken != null && !currentRawToken.isBlank()) {
            sessionStore.revokeOthersForUser(user.id, currentRawToken);
        }
        LOGGER.info("Password changed for user: {} (other sessions revoked)", user.email);
        return new PasswordChangeResult.Success();
    }

    /**
     * The English rejection sentence for a {@link PasswordRejection} - what the API resource still calls directly (it stays
     * English by design); the web resource instead resolves a translated sentence via {@code partials/text-failure-message.html} for the
     * {@code TooLong} cause, or its own fixed-shape entries for {@code Mismatch}/{@code Unchanged}.
     *
     * @param rejection the rejection cause
     * @return the message, in English
     */
    @NotUiFacing(reason = "the /api/v1 400 body; the Settings password card renders partials/text-failure-message.html and its own entries")
    public static String message(final PasswordRejection rejection) {
        return switch (rejection) {
            case final PasswordRejection.Mismatch _ -> "Passwords do not match";
            case final PasswordRejection.TooLong tooLong -> TextOutcomeExtensions.message(tooLong.failure());
            case final PasswordRejection.Unchanged _ -> "New password must be different from the existing password";
        };
    }

    // Only accounts HOLDING a password can verify or change one; OIDC-only accounts have none. Deliberately
    // independent of PASSWORD_AUTH_ENABLED: with password login off, the one password-holding account is the
    // break-glass administrator from the first-run setup, and it must be able to maintain that credential.
    private static boolean notLocalAccount(final User user) {
        return user.passwordHash == null || user.passwordHash.isBlank();
    }

    private boolean currentPasswordMismatch(final User user, final @Nullable String currentPassword) {
        // The notLocalAccount guard has already established a non-blank stored hash.
        return currentPassword == null || currentPassword.isEmpty()
                || !passwords.matches(currentPassword, java.util.Objects.requireNonNull(user.passwordHash));
    }
}
