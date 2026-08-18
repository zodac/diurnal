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
import java.util.List;
import net.zodac.diurnal.user.User;

/**
 * The outcome of an account registration by {@link RegistrationService}: the caller (the web form or the REST API) maps each case to its own response
 * medium — a re-rendered form with banners, or a JSON error + status code. Mirrors the {@link LoginResult} pattern shared by the two login surfaces,
 * so the registration rules cannot diverge between the surfaces (only their presentation can).
 */
public sealed interface RegistrationResult
    permits RegistrationResult.Success, RegistrationResult.LockedOut, RegistrationResult.Invalid, RegistrationResult.DuplicateEmail {

    /**
     * The account was created; {@link #user()} is the new account for which a session should now be minted.
     *
     * @param user the newly registered user
     */
    record Success(User user) implements RegistrationResult {

    }

    /**
     * The attempt was blocked by the shared per-IP lockout; {@link #remaining()} is roughly how long until it may be retried.
     *
     * @param remaining the time left on the lockout
     */
    record LockedOut(Duration remaining) implements RegistrationResult {

    }

    /**
     * The submission failed validation. The failure was recorded against the per-IP throttle before returning.
     *
     * @param missingFields the required fields that were left blank
     * @param errors        the causes for the fields that were present but rejected
     */
    record Invalid(List<RequiredField> missingFields, List<RegistrationError> errors) implements RegistrationResult {

    }

    /**
     * The (normalised) email is already registered. The failure was recorded against the per-IP throttle before returning.
     */
    record DuplicateEmail() implements RegistrationResult {

    }

    /**
     * A required registration field, named so a blank submission can be reported without an opaque, English-only label. {@code CONFIRM_PASSWORD} is
     * a web-form-only field with no {@code TextFields} catalogue entry of its own (the API has no confirmation field at all - see
     * {@link RegistrationService#register}).
     */
    enum RequiredField {

        /** The account email. */
        EMAIL("email"),
        /** The account display name. */
        DISPLAY_NAME("displayName"),
        /** The account password. */
        PASSWORD("password"),
        /** The web form's re-entered password. */
        CONFIRM_PASSWORD("confirmPassword");

        private final String key;

        RequiredField(final String key) {
            this.key = key;
        }

        /**
         * A stable, non-English identifier for this field, used to switch on it in a translated template.
         *
         * @return the field key
         */
        public String key() {
            return key;
        }
    }
}
