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

package net.zodac.diurnal.auth.lockout;

/**
 * The outcome of an administrator manually unlocking a client IP through {@link IpLockoutService}: the caller (the admin page's HTMX resource or the
 * REST API's {@code /api/v1/admin/ip-lockouts}) maps each case to its own response medium. Mirrors the {@code AdminUserResult} pattern shared by the
 * two admin-user surfaces, so the unlock rule cannot diverge between the surfaces (only its presentation can).
 */
public sealed interface IpUnlockResult permits IpUnlockResult.Success, IpUnlockResult.NotLocked {

    /**
     * The IP was locked and has been cleared.
     *
     * @param ipAddress the IP that was unlocked
     */
    record Success(String ipAddress) implements IpUnlockResult {

    }

    /**
     * The IP was not currently locked out, so there was nothing to unlock.
     */
    record NotLocked() implements IpUnlockResult {

    }
}
