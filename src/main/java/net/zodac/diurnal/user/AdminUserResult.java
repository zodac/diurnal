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

package net.zodac.diurnal.user;

/**
 * The outcome of an administrative user mutation by {@link AdminUserService}: the caller (the admin page's HTMX resource or the REST API's
 * {@code /api/v1/admin/users}) maps each case to its own response medium. Mirrors the {@code LoginResult} pattern shared by the two login surfaces,
 * so the management rules — most importantly the last-administrator safeguards — cannot diverge between the surfaces (only their presentation can).
 */
public sealed interface AdminUserResult
    permits AdminUserResult.Success, AdminUserResult.NotFound, AdminUserResult.InvalidRole, AdminUserResult.LastAdmin {

    /**
     * The mutation succeeded; {@link #user()} is the affected account.
     *
     * @param user the affected user
     */
    record Success(User user) implements AdminUserResult {

    }

    /**
     * No account exists with the given id.
     */
    record NotFound() implements AdminUserResult {

    }

    /**
     * The submitted role is not a recognised role value.
     */
    record InvalidRole() implements AdminUserResult {

    }

    /**
     * The mutation would remove the last administrator (by demotion or deletion) and was refused.
     */
    record LastAdmin() implements AdminUserResult {

    }
}
