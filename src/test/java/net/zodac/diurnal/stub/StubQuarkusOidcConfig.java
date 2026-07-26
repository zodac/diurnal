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

package net.zodac.diurnal.stub;

import net.zodac.diurnal.config.QuarkusOidcConfig;

/**
 * Reusable {@link QuarkusOidcConfig} stub built from its record components - the framework-owned {@code quarkus.oidc.*} keys the application reads.
 *
 * @param tenantEnabled whether the default OIDC tenant is enabled
 * @param authServerUrl the IdP base (issuer) URL
 * @param discoveryEnabled whether OIDC discovery is enabled
 */
public record StubQuarkusOidcConfig(boolean tenantEnabled, String authServerUrl, boolean discoveryEnabled) implements QuarkusOidcConfig {

}
