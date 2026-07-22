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

import java.net.HttpURLConnection;
import java.util.Optional;

/**
 * The pure decision core of the startup OIDC discovery probe (unit-tested to full mutation strength). Quarkus fetches the identity provider's
 * discovery document lazily, on the first login attempt, so a misconfigured issuer (a typo, an unreachable host, a wrong URL) boots cleanly and is
 * only discovered by the first user who is bounced to {@code /login?error=oidc}. When enabled, {@code AppLifecycle} probes the discovery endpoint at
 * startup and fails fast on a broken configuration instead; this class decides whether that probe runs, where it points, and whether a completed
 * probe indicates a healthy provider.
 *
 * <p>
 * The {@code AppLifecycle} glue owns only the HTTP call (the untestable I/O); every branch of the decision lives here.
 */
public final class OidcDiscovery {

    private static final String WELL_KNOWN_SUFFIX = "/.well-known/openid-configuration";

    private OidcDiscovery() {

    }

    /**
     * Whether the startup discovery probe should run at all. It runs only when OIDC is enabled, the operator has not opted out
     * ({@code OIDC_VERIFY_ON_STARTUP}), and Quarkus discovery is enabled - with discovery disabled the endpoints are configured manually and there is
     * no discovery document to fetch, so probing it would report a spurious failure.
     *
     * @param oidcEnabled      whether the OIDC tenant is enabled ({@code quarkus.oidc.tenant-enabled})
     * @param verifyOnStartup  whether the operator has opted in to the startup probe ({@code oidc.verify.on.startup})
     * @param discoveryEnabled whether Quarkus OIDC discovery is enabled ({@code quarkus.oidc.discovery-enabled})
     * @return {@code true} when the probe should run
     */
    public static boolean shouldVerify(final boolean oidcEnabled, final boolean verifyOnStartup, final boolean discoveryEnabled) {
        return oidcEnabled && verifyOnStartup && discoveryEnabled;
    }

    /**
     * Builds the OIDC discovery document URL for an issuer, tolerating a single trailing slash on the configured issuer.
     *
     * @param issuerUrl the configured issuer URL ({@code OIDC_ISSUER_URL})
     * @return the {@code .well-known/openid-configuration} URL to probe
     */
    public static String discoveryUrl(final String issuerUrl) {
        final String trimmed = issuerUrl.strip();
        final String base = trimmed.endsWith("/")
            ? trimmed.substring(0, trimmed.length() - 1)
            : trimmed;
        return base + WELL_KNOWN_SUFFIX;
    }

    /**
     * Classifies a completed discovery probe into a fail-fast error message, or empty when the provider is healthy. The message is plain ASCII (it
     * reaches the logs) and always names the escape hatch, so an operator hitting a transient outage can boot anyway.
     *
     * @param issuerUrl  the configured issuer URL (for the message)
     * @param reachable  whether the endpoint responded at all ({@code false} = a connection or timeout failure)
     * @param statusCode the HTTP status when reachable (ignored otherwise)
     * @param body       the response body when reachable (ignored otherwise)
     * @return the fail-fast message when the configuration is broken, or empty when the provider is healthy
     */
    public static Optional<String> validationFailure(final String issuerUrl, final boolean reachable, final int statusCode, final String body) {
        final String discoveryUrl = discoveryUrl(issuerUrl);
        if (!reachable) {
            return Optional.of("OIDC_ENABLED=true but the identity provider discovery endpoint " + discoveryUrl
                + " could not be reached. Check OIDC_ISSUER_URL and that the provider is running, or set OIDC_VERIFY_ON_STARTUP=false to skip this"
                + " check.");
        }
        if (statusCode != HttpURLConnection.HTTP_OK) {
            return Optional.of("OIDC_ENABLED=true but the identity provider discovery endpoint " + discoveryUrl
                + " returned HTTP " + statusCode + ". Check OIDC_ISSUER_URL, or set OIDC_VERIFY_ON_STARTUP=false to skip this check.");
        }
        if (!isDiscoveryDocument(body)) {
            return Optional.of("OIDC_ENABLED=true but " + discoveryUrl
                + " did not return a valid OIDC discovery document. Check OIDC_ISSUER_URL points at the provider's issuer, or set"
                + " OIDC_VERIFY_ON_STARTUP=false to skip this check.");
        }
        return Optional.empty();
    }

    /**
     * A best-effort check that a fetched body is an OIDC discovery document - a JSON object naming an {@code issuer} - rather than an unrelated page
     * (an HTML error page, an empty body) served from a mistyped URL. Full parsing is left to Quarkus; this only catches a plainly-wrong response.
     *
     * @param body the response body
     * @return {@code true} when the body looks like a discovery document
     */
    private static boolean isDiscoveryDocument(final String body) {
        final String trimmed = body.strip();
        return trimmed.startsWith("{") && trimmed.contains("\"issuer\"");
    }
}
