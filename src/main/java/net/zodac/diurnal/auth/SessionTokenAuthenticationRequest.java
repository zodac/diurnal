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

import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/**
 * Carries a raw opaque session token from {@link SessionAuthMechanism} to {@link SessionIdentityProvider}, which resolves it to a
 * {@link net.zodac.diurnal.user.User}. This indirection lets the blocking database lookup run off the IO thread via the identity-provider machinery,
 * mirroring how the built-in mechanisms hand credentials to their providers.
 */
public class SessionTokenAuthenticationRequest extends BaseAuthenticationRequest {

    private final String token;

    /**
     * Wraps the raw token presented by the client (from the session cookie or a Bearer header).
     *
     * @param token the raw opaque session token
     */
    public SessionTokenAuthenticationRequest(final String token) {
        super();
        this.token = token;
    }

    /**
     * The raw opaque session token to resolve.
     *
     * @return the token
     */
    public String getToken() {
        return token;
    }
}
