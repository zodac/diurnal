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

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile that lets the two OIDC code-flow routes be reached by a test identity.
 *
 * <p>
 * {@code application.properties} pins {@code /oidc-login} and {@code /oauth2/callback/oidc} to the {@code code} authentication mechanism, so those
 * paths can only ever be entered by an identity the OIDC code exchange itself produced. No test identity can be one: {@code @TestSecurity} is
 * refused as unauthenticated (a {@code 302} to the login page), and an enabled tenant instead answers {@code 500} because the placeholder issuer is
 * not a real provider. This profile moves that permission set onto a path nothing serves, which leaves both routes under the ordinary
 * {@code authenticated} policy their annotations already declare.
 *
 * <p>
 * <strong>What that costs, stated plainly:</strong> a test running under this profile proves what the callback DOES - the login stamp, the
 * server-side session it mints, the Settings round trip - and cannot prove the pin it is switching off, namely that a {@code diurnal_session} cookie
 * alone is refused at the callback. That guarantee lives in {@code application.properties} and is exercised by nothing here. This override exists in
 * the test JVM only: it is applied by the Quarkus test extension when it boots the app for the annotated class, changes no file, and reaches neither
 * the packaged application nor any other test.
 */
public final class OidcCallbackProfile implements QuarkusTestProfile {

    /**
     * Parks the code-mechanism permission set on a path nothing serves, with the OIDC tenant off so no absent provider is ever contacted.
     *
     * @return the config overrides applied for this profile
     */
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "quarkus.oidc.tenant-enabled", "false",
                "quarkus.http.auth.permission.oidc-trigger.paths", "/never-served-oidc-trigger");
    }
}
