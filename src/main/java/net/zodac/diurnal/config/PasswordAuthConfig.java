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

package net.zodac.diurnal.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed view over the {@code password.auth.*} settings governing the form/password login mechanism.
 */
@ConfigMapping(prefix = "password")
public interface PasswordAuthConfig {

    /**
     * Whether password-based authentication (the login form and {@code /api/auth/register}) is enabled. When {@code false} the app relies on OIDC
     * only.
     *
     * @return {@code true} when password auth is enabled, defaulting to {@code true}
     */
    @WithName("auth.enabled")
    @WithDefault("true")
    boolean enabled();

    /**
     * Whether the login path should equalise its response time across existent and non-existent accounts. When {@code true}, a login for an account
     * with no stored password hash still runs an Argon2id verification against a throwaway hash, so the response time cannot be used to enumerate
     * which emails have accounts. When {@code false}, the hash check is skipped when there is no hash to verify against (faster, but leaks account
     * existence via timing).
     *
     * @return {@code true} when uniform login timing is enabled, defaulting to {@code true}
     */
    @WithName("auth.uniform-timing.enabled")
    @WithDefault("true")
    boolean uniformTimingEnabled();
}
