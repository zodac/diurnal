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

import net.zodac.diurnal.text.TextOutcome;

/**
 * Why a present-but-rejected {@link RegistrationResult.Invalid} field failed, carried structured so the API resource can still word it in English
 * (unchanged, {@link RegistrationService#message(RegistrationError)}) while the web resource resolves a translated sentence. A top-level type,
 * rather than nested under {@link RegistrationResult} (as it originally was), so its own record variants stay at one level of nesting rather than
 * two - the same reason {@link net.zodac.diurnal.user.ProfileRejection} is top-level.
 */
public sealed interface RegistrationError permits RegistrationError.FieldError, RegistrationError.PasswordMismatch {

    /**
     * An email, display-name or password submission that broke the shared text-validation pipeline - which field is named by
     * {@code failure.field().key()}.
     *
     * @param failure the rejection
     */
    record FieldError(TextOutcome.Failure failure) implements RegistrationError {

    }

    /**
     * The web form's re-entered password did not match the password (or was submitted blank while the password was not).
     */
    record PasswordMismatch() implements RegistrationError {

    }
}
