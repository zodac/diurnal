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

package net.zodac.diurnal.auth.oidc;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed view over the handful of framework-owned {@code quarkus.oidc.*} extension settings the application reads directly (as opposed to the
 * app's own {@code oidc.*} behaviour toggles in {@link OidcConfig}). Kept to the exact keys the app inspects - the extension owns the full
 * {@code quarkus.oidc.*} surface; this mapping is only a read-only view of the three the startup validation and the settings page need.
 *
 * <p>
 * These are Quarkus' own keys rather than an application-defined {@code prefix.*} group, but they are still read through a mapping so no raw
 * {@code @ConfigProperty} lookup is scattered across the codebase (see {@code CODE_STYLE.md}).
 */
@ConfigMapping(prefix = "quarkus.oidc")
public interface QuarkusOidcConfig {

    /**
     * Whether the OIDC tenant is enabled at runtime ({@code OIDC_ENABLED}). When {@code false} the extension is inert and no IdP handshake occurs.
     *
     * @return {@code true} when OIDC is enabled, defaulting to {@code false}
     */
    @WithName("tenant-enabled")
    @WithDefault("false")
    boolean tenantEnabled();

    /**
     * The IdP's base (issuer) URL ({@code OIDC_ISSUER_URL}). Blank when OIDC is disabled; the startup validation fails fast if OIDC is enabled but
     * this is unset.
     *
     * @return the configured issuer URL, defaulting to an empty string
     */
    @WithName("auth-server-url")
    @WithDefault("")
    String authServerUrl();

    /**
     * Whether Quarkus fetches the provider's discovery document (the default) rather than using manually configured endpoints. The startup probe is
     * skipped when discovery is off, since there is no discovery document to verify.
     *
     * @return {@code true} when discovery is enabled, defaulting to {@code true}
     */
    @WithName("discovery-enabled")
    @WithDefault("true")
    boolean discoveryEnabled();
}
