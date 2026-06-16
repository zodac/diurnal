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

import io.smallrye.jwt.build.Jwt;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import net.zodac.diurnal.user.User;

/** Issues signed RSA JWTs for the REST API, encoding the user's roles as token groups. */
@ApplicationScoped
public class TokenService {

    private static final Duration TOKEN_LIFESPAN = Duration.ofDays(1L);

    /** Generates a signed JWT for the given user, valid for one day from now. */
    public String generateToken(final User user) {
        final Set<String> groups = new HashSet<>();
        groups.add(User.ROLE_USER);
        if (user.isAdmin()) {
            groups.add(User.ROLE_ADMIN);
        }
        return Jwt.issuer("diurnal")
                .subject(user.id.toString())
                .upn(user.email)
                .groups(groups)
                .claim("email", user.email)
                .claim("name", user.displayName)
                .expiresIn(TOKEN_LIFESPAN)
                .sign();
    }
}
