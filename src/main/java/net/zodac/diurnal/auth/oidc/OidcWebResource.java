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

import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import net.zodac.diurnal.auth.session.Session;
import net.zodac.diurnal.auth.session.SessionCookies;
import net.zodac.diurnal.auth.session.SessionStore;
import net.zodac.diurnal.http.ClientAddress;
import net.zodac.diurnal.http.RollbackOnErrorStatus;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The browser-facing half of OIDC sign-in: the code-flow entry point, the redirect-back callback that mints the Diurnal session, and the Settings
 * "Connect {provider}" trigger. The decisions behind them are {@link OidcUserProvisioner} and its policies — this resource only moves the browser
 * around and sets cookies.
 *
 * <p>
 * Surface policy: every route here is a browser redirect dance with the identity provider, so none has an {@code /api/v1} twin — an API client has
 * no user agent to send through the code flow.
 */
@Path("/")
@RollbackOnErrorStatus
public class OidcWebResource {

    /**
     * The {@code ?msg=} code the Settings page renders as the "connected" banner after a successful connect round trip. A failed one comes back as
     * an {@link OidcDenialReason} code instead.
     */
    public static final String MSG_OIDC_CONNECTED = "oidc-connected";

    private static final Logger LOGGER = LogManager.getLogger(OidcWebResource.class);

    // The Settings "Connect {provider}" intent marker only needs to survive the round trip to the IdP.
    private static final int LINK_INTENT_COOKIE_MAX_AGE_SECONDS = 300;

    private final SecurityIdentity identity;
    private final CurrentUser currentUser;
    private final AppClock clock;
    private final SessionStore sessionStore;
    private final SessionCookies sessionCookies;
    private final QuarkusOidcConfig quarkusOidcConfig;

    /**
     * Injects the current-request identity accessors, the session store and cookie builder, and the framework-owned OIDC keys.
     *
     * @param identity the current request's security identity
     * @param currentUser the current-user accessor
     * @param clock the application clock for date-boundary logic
     * @param sessionStore the session store used to mint the OIDC session
     * @param sessionCookies the shared session-cookie builder
     * @param quarkusOidcConfig the framework-owned {@code quarkus.oidc.*} keys (tenant-enabled)
     */
    @Inject
    public OidcWebResource(final SecurityIdentity identity, final CurrentUser currentUser, final AppClock clock, final SessionStore sessionStore,
        final SessionCookies sessionCookies, final QuarkusOidcConfig quarkusOidcConfig) {
        this.identity = identity;
        this.currentUser = currentUser;
        this.clock = clock;
        this.sessionStore = sessionStore;
        this.sessionCookies = sessionCookies;
        this.quarkusOidcConfig = quarkusOidcConfig;
    }

    /**
     * Entry point for the OIDC code flow; authenticated users are forwarded home.
     */
    @GET
    @Path("oidc-login")
    @RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
    public Response oidcLogin() {
        // Unauthenticated requests never reach here — the oidc-trigger permission policy
        // intercepts them first and issues the OIDC Authorization Code challenge.
        // Authenticated users (e.g. navigating from browser history) are forwarded home.
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Handles the OIDC redirect-back, records the login, and forwards the user to the dashboard.
     *
     * <p>
     * This route must stay in step with {@code quarkus.oidc.authentication.redirect-path} - it is the path Quarkus sends the IdP back to, and the
     * one {@link OidcUserProvisioner} exempts from the session-revocation guard. {@code OidcUserProvisionerIT} asserts the pair agree.
     */
    @GET
    @Path("oauth2/callback/oidc")
    @PermitAll
    @Transactional
    public Response oidcCallback(@CookieParam(OidcUserProvisioner.LINK_COOKIE) @Nullable final String linkIntent,
        @Context @Nullable final RoutingContext routingContext) {
        // The oidc-trigger permission pins this path to the code mechanism, so when the IdP
        // redirects back here CodeAuthenticationMechanism exchanges the code, validates the
        // tokens and creates the OIDC session cookie. The request then reaches JAX-RS — this
        // endpoint receives it and forwards the now-authenticated user to the dashboard.
        //
        // This runs exactly once per OIDC login, so it is where we record the login AND mint a
        // Diurnal server-side session (auth_source='oidc'), setting our diurnal_session cookie. From
        // here on SessionAuthMechanism authenticates every request from that cookie, so OIDC and
        // password users share one revocable session model. The q_session cookie is left in place
        // only so logout can still trigger RP-initiated IdP logout.
        final Optional<User> found = identity.isAnonymous() ? Optional.empty() : currentUser.find();
        if (found.isEmpty()) {
            return Response.seeOther(URI.create("/")).build();
        }

        final User user = found.get();
        user.lastLoginAt = Instant.now();
        user.persist();
        LOGGER.debug("OIDC login: name={} email={} role={}", user.displayName, user.email, user.role);
        final String token = sessionStore.create(
            user, Session.AUTH_SOURCE_OIDC, SessionCookies.userAgent(routingContext), ClientAddress.of(routingContext), clock.now());
        if (linkIntent != null) {
            // A Settings "Connect" round trip: the link itself was applied during authentication by OidcUserProvisioner and OidcLinkPolicy.
            // Clear the one-shot intent marker and land back on Settings with a success banner instead of the dashboard.
            final NewCookie clearIntent =
                new NewCookie.Builder(OidcUserProvisioner.LINK_COOKIE).value("").path("/").maxAge(0).httpOnly(true).build();
            return Response.seeOther(URI.create("/settings?msg=" + MSG_OIDC_CONNECTED))
                .cookie(sessionCookies.issued(token, routingContext), clearIntent)
                .build();
        }
        return Response.seeOther(URI.create("/")).cookie(sessionCookies.issued(token, routingContext)).build();
    }

    // ── Identity-provider connection (Settings → Account) ──────────────────

    /**
     * Starts the Settings "Connect {provider}" flow: sets the short-lived link-intent cookie and forwards into the OIDC code flow. The actual link
     * is applied during the callback's authentication ({@code OidcUserProvisioner} + {@code OidcLinkPolicy}), keyed on this cookie plus the
     * signed-in session — an email match alone can never link while password auth is enabled. Connecting is a one-way conversion: the account's
     * password is removed in the same step ({@code net.zodac.diurnal.auth.oidc.AccountLinkService#link}), so the confirmation step in the Settings UI
     * warns about exactly that.
     *
     * <p>
     * Surface policy: the flow is a browser redirect dance with the identity provider, so it deliberately has no {@code /api/v1} twin — an API
     * client has no user agent to send through the code flow.
     */
    @POST
    @Path("internal/settings/oidc/connect")
    @RolesAllowed(Role.Values.USER_INTERNAL_VALUE)
    public Response connectOidc() {
        if (!quarkusOidcConfig.tenantEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        final NewCookie intent = new NewCookie.Builder(OidcUserProvisioner.LINK_COOKIE)
            .value("1")
            .path("/")
            .maxAge(LINK_INTENT_COOKIE_MAX_AGE_SECONDS)
            .httpOnly(true)
            .build();
        return Response.seeOther(URI.create("/oidc-login")).cookie(intent).build();
    }
}
