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

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed view over the {@code registration.local.*} settings controlling the creation of LOCAL (password) accounts - the {@code /register} page and
 * {@code POST /api/v1/auth/register}.
 *
 * <p>
 * The switch is deliberately scoped to local accounts: OIDC sign-in provisions an account on first login through a separate path ({@code auth.oidc})
 * that never consults it, so turning this off does not close account creation in an OIDC-enabled deployment. Restricting WHO the identity provider
 * may bring in is {@code oidc.user.group}/{@code oidc.admin.group} (a non-member is refused before any account is provisioned), or the provider's
 * own access rules.
 */
@FunctionalInterface
@ConfigMapping(prefix = "registration")
public interface LocalRegistrationConfig {

    /**
     * Whether new local (password) accounts may be created through the UI or the API. OIDC accounts are provisioned on first login regardless of
     * this setting.
     *
     * <p>
     * Ignored during first-run setup, which must always be able to create the initial local account.
     *
     * @return {@code true} when local registration is enabled, defaulting to {@code true}
     */
    @WithName("local.enabled")
    @WithDefault("true")
    boolean enabled();
}
