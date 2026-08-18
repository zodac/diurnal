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
 * Why a new password was rejected - three distinct causes that used to share one opaque, English-only {@code String} on
 * {@code PasswordChangeResult.InvalidNewPassword}. Carried structured so the API resource can still word it in English (unchanged,
 * {@code PasswordChangeService}'s existing constants) while the web resource resolves a translated sentence. A top-level type, rather than nested
 * under {@link PasswordChangeResult} (as it originally was), so its own record variants stay at one level of nesting rather than two - the same
 * reason {@link net.zodac.diurnal.user.ProfileRejection} is top-level.
 */
public sealed interface PasswordRejection permits PasswordRejection.Mismatch, PasswordRejection.TooLong, PasswordRejection.Unchanged {

    /**
     * The re-entered new password did not match (the web form's confirm-password step), or no new password was submitted at all - an absent
     * password is reported with this same wording rather than the text-validation pipeline's blank message, since (from the user's point of view)
     * it is a rejection of the same "the two boxes must agree" step.
     */
    record Mismatch() implements PasswordRejection {

    }

    /**
     * The new password exceeds {@code TextFields#PASSWORD}'s maximum length. Carried as the raw {@link TextOutcome.Failure} for the same reason as
     * {@code ActionResult.InvalidName}/{@code NoteResult.Invalid}.
     *
     * @param failure the rejection (always a {@code TextOutcome.TooLong})
     */
    record TooLong(TextOutcome.Failure failure) implements PasswordRejection {

    }

    /**
     * The new password is identical to the current one.
     */
    record Unchanged() implements PasswordRejection {

    }
}
