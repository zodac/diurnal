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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import net.zodac.diurnal.IntegrationTestBase;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the Settings "Connect {provider}" surface over {@link AccountLinkService}: the connect trigger (intent cookie + code-flow
 * forward), the one-way conversion the service applies (linking removes the password), and the Settings page reflecting the link state. Runs under
 * {@link OidcEnabledProfile} so the OIDC-gated rendering behaves deterministically in every environment.
 */
@QuarkusTest
@TestProfile(OidcEnabledProfile.class)
class AccountLinkIT extends IntegrationTestBase {

    private static final String OIDC_ONLY = "oidc-only@lt.test";
    private static final String LOCAL = "local@lt.test";
    private static final String OIDC_ISSUER = "https://diurnal.example.com/idp";

    @Inject
    AccountLinkService accountLinkService;

    @Override
    protected void createDbState() {
        final User oidcOnly = newUser(OIDC_ONLY, "OIDC Only", Role.USER.storageValue());
        oidcOnly.passwordHash = null; // NOPMD: NullAssignment - seeding a password-less OIDC-only account
        oidcOnly.oidcIssuer = OIDC_ISSUER;
        oidcOnly.oidcSubject = "subject-oidc-only";
        oidcOnly.persist();

        newUser(LOCAL, "Local User");
    }

    // ── AccountLinkService.link: a one-way conversion ─────────────────────────

    @Test
    void link_localAccount_attachesIdentityAndRemovesPassword() {
        runInTx(() -> accountLinkService.link(User.findByEmail(LOCAL).orElseThrow(), OIDC_ISSUER, "subject-local"));

        runInTx(() -> assertThat(User.findByEmail(LOCAL).orElseThrow().authSource())
            .as("Connecting must convert the account to OIDC-only sign-in (identity attached, password removed)")
            .isEqualTo("oidc"));
    }

    @Test
    void link_identityAlreadyOwnedByAnotherAccount_throwsAndChangesNothing() {
        runInTx(() -> assertThatThrownBy(() -> accountLinkService.link(User.findByEmail(LOCAL).orElseThrow(), OIDC_ISSUER, "subject-oidc-only"))
            .as("An identity already linked to another account must never be re-linked")
            .isInstanceOf(IllegalStateException.class));

        runInTx(() -> assertThat(User.findByEmail(LOCAL).orElseThrow().passwordHash)
            .as("A refused link must leave the account untouched")
            .isNotNull());
    }

    @Test
    void link_alreadyLinkedAccount_throws() {
        runInTx(() -> assertThatThrownBy(() -> accountLinkService.link(User.findByEmail(OIDC_ONLY).orElseThrow(), OIDC_ISSUER, "subject-new"))
            .as("A linked account must not be re-linked to a different identity")
            .isInstanceOf(IllegalStateException.class));
    }

    // ── The Settings connect trigger ──────────────────────────────────────────

    @Test
    @TestSecurity(user = LOCAL, roles = Role.Values.USER)
    void webConnect_localAccount_setsIntentCookieAndEntersCodeFlow() {
        given().redirects().follow(false)
                .post("/internal/settings/oidc/connect")
                .then().statusCode(303)
                .header("Location", endsWith("/oidc-login"))
                .cookie(OidcUserProvisioner.LINK_COOKIE, "1");
    }

    // ── The Settings page reflects the link state ─────────────────────────────

    @Test
    @TestSecurity(user = OIDC_ONLY, roles = Role.Values.USER)
    void settingsPage_linkedAccount_showsConnectedStateLinkingToTheIdp() {
        // The provider name links to the IdP's base URL (OIDC_ISSUER_URL — the profile's placeholder realm here).
        given().get("/settings")
                .then().statusCode(200)
                .body(containsString("Connected to"))
                .body(containsString("href=\"http://127.0.0.1:8080/realms/diurnal\""));
    }

    @Test
    @TestSecurity(user = LOCAL, roles = Role.Values.USER)
    void settingsPage_refusedConnectCode_rendersTheReasonBannerInPlace() {
        // A refused connect redirects back HERE (?msg=<OidcDenialReason code>) with the session intact — never to the login page.
        given().get("/settings?msg=link-email-mismatch")
                .then().statusCode(200)
                .body(containsString("uses a different email address"));
    }

    @Test
    @TestSecurity(user = LOCAL, roles = Role.Values.USER)
    void settingsPage_localAccount_offersConnectWithConversionWarning() {
        given().get("/settings")
                .then().statusCode(200)
                .body(containsString("/internal/settings/oidc/connect"))
                .body(containsString("Your password will be removed"));
    }
}
