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

package net.zodac.diurnal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CspPolicy#forPath(String)}: the admin-gated OpenAPI documentation surface gets a relaxed policy, and every other route —
 * including JAX-RS endpoints nested under {@code /api/...} — gets the app's strict policy.
 */
class CspPolicyTest {

    private static final String FOUC_STYLE_CONTENT = "body{background-color:#111827!important;color:#f9fafb!important}";

    @Test
    void forPath_swaggerUiShell_isRelaxed() {
        final String policy = CspPolicy.forPath("/api");
        assertThat(policy)
                .as("The Swagger UI shell must get the relaxed documentation policy")
                .contains("'unsafe-inline'");
    }

    @Test
    void forPath_swaggerUiShellWithIndexHtml_isRelaxed() {
        final String policy = CspPolicy.forPath("/api/index.html");
        assertThat(policy)
                .as("The Swagger UI shell's index.html variant must get the relaxed documentation policy")
                .contains("'unsafe-inline'");
    }

    @Test
    void forPath_openApiDocument_isRelaxed() {
        final String policy = CspPolicy.forPath("/q/openapi");
        assertThat(policy)
                .as("The generated OpenAPI document must get the relaxed documentation policy")
                .contains("'unsafe-inline'");
    }

    @Test
    void forPath_openApiDocumentJsonVariant_isRelaxed() {
        final String policy = CspPolicy.forPath("/q/openapi.json");
        assertThat(policy)
                .as("The OpenAPI document's .json variant must get the relaxed documentation policy")
                .contains("'unsafe-inline'");
    }

    @Test
    void forPath_apiJaxRsEndpoint_isStrict() {
        final String policy = CspPolicy.forPath("/api/v1/auth/login");
        assertThat(policy)
                .as("A JAX-RS endpoint nested under /api/... must NOT get the relaxed documentation policy — the"
                        + " only 'unsafe-inline' the strict policy ever carries is style-src-attr's pragmatic"
                        + " allowance for dynamic swatch colours, never script-src or style-src")
                .doesNotContain("script-src 'self' 'unsafe-inline'")
                .doesNotContain("style-src 'self' 'unsafe-inline'");
    }

    @Test
    void forPath_dashboard_isStrict() {
        final String policy = CspPolicy.forPath("/");
        assertThat(policy)
                .as("A regular user-facing route must lock down script-src-attr and never allow unsafe-inline scripts")
                .contains("script-src-attr 'none'")
                .doesNotContain("script-src 'self' 'unsafe-inline'");
    }

    @Test
    void forPath_dashboard_styleSrcAttrAllowsInlineForDynamicSwatchColours() {
        final String policy = CspPolicy.forPath("/");
        assertThat(policy)
                .as("style-src-attr must stay 'unsafe-inline' — the app renders per-user swatch colours as"
                        + " inline style= attributes (the policy's one deliberate laxity)")
                .contains("style-src-attr 'unsafe-inline'");
    }

    @Test
    void forPath_dashboard_styleSrcCoversFoucStyleHashButNoUnsafeInline() {
        final String policy = CspPolicy.forPath("/");
        final String hash = sha256Base64(FOUC_STYLE_CONTENT.getBytes(StandardCharsets.UTF_8));
        assertThat(policy)
                .as("style-src must allow only the FOUC background-override <style>'s pinned hash, not 'unsafe-inline'"
                        + " (that laxity is scoped to style-src-attr only)")
                .contains("style-src 'self' 'sha256-" + hash + "'")
                .doesNotContain("style-src 'self' 'unsafe-inline'");
    }

    @Test
    void forPath_dashboard_closesRemainingFetchDirectivesToSelf() {
        final String policy = CspPolicy.forPath("/");
        assertThat(policy)
                .as("No data: URI, cross-origin font, or cross-origin fetch/HTMX target"
                        + " exists anywhere in the app, so default-src/img-src/font-src/connect-src all stay 'self'")
                .contains("default-src 'self'")
                .contains("img-src 'self'")
                .contains("font-src 'self'")
                .contains("connect-src 'self'")
                .doesNotContain("data:");
    }

    @Test
    void forPath_swaggerUiShell_doesNotInheritStrictFetchDirectives() {
        final String policy = CspPolicy.forPath("/api");
        assertThat(policy)
                .as("The relaxed documentation policy must not pick up the strict policy's default-src/font-src/"
                        + "connect-src — it only needs img-src 'self' data: for Swagger UI's embedded assets")
                .doesNotContain("default-src")
                .doesNotContain("font-src")
                .doesNotContain("connect-src")
                .contains("img-src 'self' data:");
    }

    private static String sha256Base64(final byte[] bytes) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(bytes));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
