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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.zodac.diurnal.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for {@link SecurityHeadersFilter}: the {@code Content-Security-Policy} header
 * carries the correct policy variant per path, and — the load-bearing check — the pinned FOUC-script
 * hash in {@link CspPolicy} always matches the actual rendered bytes of the inline theme bootstrap in
 * {@code layout.html}. A future edit to that script that isn't followed by re-pinning the hash breaks
 * this test instead of silently breaking the CSP for every page.
 */
@QuarkusTest
class SecurityHeadersFilterIT extends IntegrationTestBase {

    // The FOUC bootstrap is the only bare `<script>` tag (no `src=`) rendered on any page.
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL);

    @Override
    protected void createDbState() {
        newUser("csp-it@lt.test", "CSP Test User");
    }

    @Test
    void login_inlineFoucScript_hashMatchesPinnedCspHash() {
        final Response response = given().get("/login").then().statusCode(200).extract().response();

        final Matcher matcher = INLINE_SCRIPT.matcher(response.asString());
        assertThat(matcher.find())
                .as("The login page must render exactly one bare <script> tag (the FOUC bootstrap)")
                .isTrue();

        final String hash = sha256Base64(matcher.group(1).getBytes(StandardCharsets.UTF_8));
        final String cspHeader = response.header("Content-Security-Policy");
        assertThat(cspHeader)
                .as("The CSP header's script-src must contain the FOUC script's actual rendered hash")
                .contains("'sha256-" + hash + "'");
    }

    @Test
    void login_stringSrcAttr_isNone() {
        given().get("/login")
                .then().statusCode(200)
                .header("Content-Security-Policy", containsString("script-src-attr 'none'"));
    }

    @Test
    void login_fetchDestinationDirectives_areLockedToSelf() {
        given().get("/login")
                .then().statusCode(200)
                .header("Content-Security-Policy", containsString("default-src 'self'"))
                .header("Content-Security-Policy", containsString("img-src 'self'"))
                .header("Content-Security-Policy", containsString("font-src 'self'"))
                .header("Content-Security-Policy", containsString("connect-src 'self'"));
    }

    @Test
    void swaggerUiShell_getsRelaxedPolicy() {
        given().redirects().follow(false)
                .get("/api")
                .then().header("Content-Security-Policy", containsString("'unsafe-inline'"));
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
