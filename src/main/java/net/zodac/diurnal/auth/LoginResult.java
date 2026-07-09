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
import net.zodac.diurnal.user.User;

/**
 * The outcome of a credential check by {@link AuthenticationService}: the caller (web form or REST
 * API) maps each case to its own response — a session + redirect/token on success, a bad-credentials
 * error, or a lockout with the remaining time.
 */
public sealed interface LoginResult permits LoginResult.Success, LoginResult.InvalidCredentials, LoginResult.LockedOut {

    /**
     * The credentials were valid; {@link #user()} is the authenticated account for which a session
     * should now be created.
     *
     * @param user the authenticated user
     */
    record Success(User user) implements LoginResult {

    }

    /**
     * The email/password pair did not match (or password auth is disabled). Deliberately
     * indistinguishable from an unknown account, so account existence is never disclosed.
     */
    record InvalidCredentials() implements LoginResult {

    }

    /**
     * The attempt was blocked by login throttling; {@link #remaining()} is roughly how long until it
     * may be retried.
     *
     * @param remaining the time left on the lockout
     */
    record LockedOut(Duration remaining) implements LoginResult {

    }
}
