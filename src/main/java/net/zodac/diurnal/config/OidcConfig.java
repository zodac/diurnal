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
import java.util.Optional;

/**
 * Typed view over the application's own {@code oidc.*} settings (the values sourced from the {@code OIDC_*} environment variables in
 * {@code application.properties}).
 *
 * <p>
 * This is deliberately separate from Quarkus' built-in {@code quarkus.oidc.*} extension config: these keys drive <em>our</em> behaviour (login button
 * label, auto-redirect, RP-initiated logout URL, LDAP group→role mapping), not the OIDC handshake itself.
 *
 * <p>
 * The {@link WithName} values pin the exact dotted property names so the existing {@code application.properties} keys and {@code OIDC_*} env-var
 * bindings are unchanged.
 */
@ConfigMapping(prefix = "oidc")
public interface OidcConfig {

    /**
     * Display name for the IdP shown on the login button (e.g. "Log in with Authelia").
     *
     * @return the configured provider name, defaulting to {@code "your identity provider"}
     */
    @WithName("provider.name")
    @WithDefault("your identity provider")
    String providerName();

    /**
     * Whether {@code /login} immediately redirects to the OIDC flow instead of rendering the login page. Only has an effect when OIDC is enabled.
     *
     * @return {@code true} to auto-redirect, defaulting to {@code false}
     */
    @WithName("auto.redirect")
    @WithDefault("false")
    boolean autoRedirect();

    /**
     * Whether the application probes the IdP's discovery endpoint at startup (failing fast on an unreachable or invalid provider) instead of letting
     * the misconfiguration surface at the first user's login. Only has an effect when OIDC is enabled and Quarkus discovery is enabled. Set
     * {@code false} to skip the probe (e.g. the screenshot generator's dummy IdP, which is never contacted).
     *
     * @return {@code true} to verify the provider at startup, defaulting to {@code true}
     */
    @WithName("verify.on.startup")
    @WithDefault("true")
    boolean verifyOnStartup();

    /**
     * RP-initiated logout URL at the IdP, used to end the upstream session on logout.
     *
     * @return the logout URL, or empty when unset
     */
    @WithName("logout.url")
    Optional<String> logoutUrl();

    /**
     * LDAP group name that maps to the {@code admin} role for OIDC-provisioned accounts.
     *
     * @return the admin group name, or empty when unset
     */
    @WithName("admin.group")
    Optional<String> adminGroup();

    /**
     * LDAP group name that maps to the {@code user} role for OIDC-provisioned accounts.
     *
     * @return the user group name, or empty when unset
     */
    @WithName("user.group")
    Optional<String> userGroup();
}
