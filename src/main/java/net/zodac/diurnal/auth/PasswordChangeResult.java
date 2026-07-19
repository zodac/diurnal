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

/**
 * The outcome of a password verification or change by {@link PasswordChangeService}: the caller (the Settings page or the REST API's
 * {@code PUT /api/v1/users/me/password}) maps each case to its own response medium. Mirrors the {@link LoginResult} pattern shared by the two login
 * surfaces, so the password-change rules cannot diverge between the surfaces (only their presentation can).
 */
public sealed interface PasswordChangeResult
    permits PasswordChangeResult.Success, PasswordChangeResult.NotLocalAccount, PasswordChangeResult.WrongCurrentPassword,
    PasswordChangeResult.InvalidNewPassword {

    /**
     * The password was verified/changed. On a change, every <em>other</em> session was revoked; the calling session stays signed in.
     */
    record Success() implements PasswordChangeResult {

    }

    /**
     * The account has no password to verify or change (OIDC-only sign-in).
     */
    record NotLocalAccount() implements PasswordChangeResult {

    }

    /**
     * The submitted current password did not match the stored hash (or was missing). Nothing was changed.
     */
    record WrongCurrentPassword() implements PasswordChangeResult {

    }

    /**
     * The submitted new password was rejected; nothing was changed.
     *
     * @param message the human-readable rejection reason (the same wording on every surface)
     */
    record InvalidNewPassword(String message) implements PasswordChangeResult {

    }
}
