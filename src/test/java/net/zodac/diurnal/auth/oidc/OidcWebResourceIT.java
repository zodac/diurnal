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

import static io.restassured.RestAssured.given;
import static net.zodac.diurnal.http.HttpStatusCodes.SEE_OTHER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.auth.session.Session;
import net.zodac.diurnal.auth.session.SessionConfig;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * What the two OIDC code-flow routes do once the identity provider has done its part: the login trigger a signed-in user reaches by navigating back
 * to it, and the callback the provider redirects to.
 *
 * <p>
 * The authentication itself is not under test here - by the time either route runs, Quarkus has exchanged the code and {@link OidcUserProvisioner}
 * has resolved the account ({@code OidcGroupProvisioningIT} covers that half). What these pin is the work the callback does exactly ONCE per login
 * and nothing else does: stamping the login on the account, minting the revocable Diurnal session every later request authenticates from, and
 * choosing between the dashboard and the Settings round trip.
 *
 * <p>
 * Reaching the routes at all needs {@link OidcCallbackProfile}, whose Javadoc states which guarantee that costs.
 */
@QuarkusTest
@TestProfile(OidcCallbackProfile.class)
class OidcWebResourceIT extends IntegrationTestBase {

    private static final String PRIMARY = "oidc-callback@lt.test";
    private static final String CALLBACK_PATH = "/oauth2/callback/oidc";

    @Inject
    SessionConfig sessionConfig;

    @Override
    protected void createDbState() {
        newUser(PRIMARY, "OIDC User", Role.USER.storageValue());
    }

    @Test
    @TestSecurity(user = PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
    void oidcLogin_alreadySignedIn_isForwardedHome() {
        // There is nothing to start: in production an unauthenticated request never reaches this route (the code mechanism challenges it first), so
        // the only visitor it ever serves is somebody arriving from browser history.
        given().redirects().follow(false)
            .get("/oidc-login")
            .then().statusCode(SEE_OTHER)
            .header("Location", endsWith("/"));
    }

    @Test
    @TestSecurity(user = PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
    void oidcCallback_mintsAServerSideSessionAndLandsOnTheDashboard() {
        final Response response = given().redirects().follow(false)
            .get(CALLBACK_PATH)
            .then().statusCode(SEE_OTHER)
            .header("Location", endsWith("/"))
            .extract().response();

        assertThat(response.getCookie(sessionConfig.cookieName()))
            .as("the callback is the one place an OIDC login mints the revocable session every later request authenticates from")
            .isNotBlank();
        runInTx(() -> assertThat(User.findByEmail(PRIMARY).orElseThrow().lastLoginAt)
            .as("and the one place an OIDC login is stamped on the account")
            .isNotNull());
        runInTx(() -> assertThat(Session.<Session>listAll())
            .as("the session is recorded as an OIDC one, so both sign-in routes share one revocable model")
            .singleElement()
            .extracting(session -> session.authSource)
            .isEqualTo(Session.AUTH_SOURCE_OIDC));
    }

    @Test
    @TestSecurity(user = PRIMARY, roles = Role.Values.USER_INTERNAL_VALUE)
    void oidcCallback_withTheLinkIntentCookie_landsBackOnSettingsAndClearsTheMarker() {
        // The Settings "Connect" round trip: the link itself was applied during authentication, so this leg only lands the user back where they
        // started - being returned to the dashboard would read as having been signed out and back in.
        final Response response = given().redirects().follow(false)
            .cookie(OidcUserProvisioner.LINK_COOKIE, "1")
            .get(CALLBACK_PATH)
            .then().statusCode(SEE_OTHER)
            .header("Location", containsString("/settings"))
            .header("Location", containsString(OidcWebResource.MSG_OIDC_CONNECTED))
            .extract().response();

        assertThat(response.getDetailedCookie(OidcUserProvisioner.LINK_COOKIE).getMaxAge())
            .as("the intent marker is one-shot, and is expired by the leg that consumes it")
            .isZero();
        assertThat(response.getCookie(sessionConfig.cookieName()))
            .as("a connect round trip still mints the session, exactly as an ordinary sign-in does")
            .isNotBlank();
    }
}
