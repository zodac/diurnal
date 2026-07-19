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

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.zodac.diurnal.auth.AuthenticationService;
import net.zodac.diurnal.auth.ClientAddress;
import net.zodac.diurnal.auth.LockoutMessages;
import net.zodac.diurnal.auth.LoginResult;
import net.zodac.diurnal.auth.OidcDenialReason;
import net.zodac.diurnal.auth.OidcUserProvisioner;
import net.zodac.diurnal.auth.PasswordChangeResult;
import net.zodac.diurnal.auth.PasswordChangeService;
import net.zodac.diurnal.auth.PasswordConstraints;
import net.zodac.diurnal.auth.RegistrationResult;
import net.zodac.diurnal.auth.RegistrationService;
import net.zodac.diurnal.auth.Session;
import net.zodac.diurnal.auth.SessionStore;
import net.zodac.diurnal.config.IpThrottleConfig;
import net.zodac.diurnal.config.OidcConfig;
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.config.RegistrationConfig;
import net.zodac.diurnal.config.SessionConfig;
import net.zodac.diurnal.stats.ActionStatField;
import net.zodac.diurnal.stats.StatsService;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.CalendarView;
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Font;
import net.zodac.diurnal.user.ProfileResult;
import net.zodac.diurnal.user.ProfileService;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.Theme;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.user.UserSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

/**
 * Serves the top-level web UI pages: login, register, logout, settings and the dashboard.
 */
@Path("/")
public class WebResource { // NOPMD: TooManyFields - single web-page controller; injected collaborators are inherent to the role

    private static final Logger LOGGER = LogManager.getLogger(WebResource.class);

    // Password-change error bodies. Returned to (and matched by) the settings client so it can route the
    // Carries the exact seconds left on a lockout to the AJAX form handlers (app.js), which post via fetch
    // and so never render the server-side banner — they run a live mm:ss countdown from this value instead.
    // Shared by both the login (GET /login render) and registration (POST /register 429) surfaces.
    private static final String LOCKOUT_RETRY_AFTER_HEADER = "X-Lockout-Retry-After";

    // Short-lived cookie signalling that a just-rejected form login was a lockout (not a bad password).
    // Its value is the seconds left; the GET /login render reads it to show the banner and seed the
    // countdown, then clears it. Only needs to survive the immediate redirect to the login page.
    private static final String LOCKOUT_COOKIE = "diurnal_login_lockout";
    private static final int LOCKOUT_COOKIE_MAX_AGE_SECONDS = 30;

    // The Settings "Connect {provider}" intent marker only needs to survive the round trip to the IdP.
    private static final int LINK_INTENT_COOKIE_MAX_AGE_SECONDS = 300;

    // The ?msg= code for a successful connect round trip (failure codes are OidcDenialReason codes).
    private static final String MSG_OIDC_CONNECTED = "oidc-connected";

    @Inject
    @Location("login")
    Template loginTemplate;

    @Inject
    @Location("register")
    Template registerTemplate;

    @Inject
    @Location("dashboard")
    Template dashboardTemplate;

    @Inject
    @Location("settings")
    Template settingsTemplate;

    @Inject
    @Location("setup")
    Template setupTemplate;

    @Inject SecurityIdentity identity;

    @Inject CurrentUser currentUser;

    @Inject StatsService statsService;

    @Inject AppClock clock;

    @Inject AuthenticationService authenticationService;

    @Inject RegistrationService registrationService;

    @Inject ProfileService profileService;

    @Inject PasswordChangeService passwordChangeService;

    @Inject SessionStore sessionStore;

    @Inject SessionConfig sessionConfig;

    @Context
    @Nullable
    RoutingContext routingContext;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    // The IdP's base URL (OIDC_ISSUER_URL): the Settings "Connected to {provider}" text links the provider name to it.
    // Initialised to satisfy NullAway; CDI overwrites it with the config value on bean creation.
    @ConfigProperty(name = "quarkus.oidc.auth-server-url", defaultValue = "")
    String oidcIssuerUrl = "";

    @Inject
    OidcConfig oidcConfig;

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @Inject
    RegistrationConfig registrationConfig;

    @Inject
    IpThrottleConfig ipThrottleConfig;

    // ── Login ──────────────────────────────────────────────────────────────

    /**
     * Renders the login page, optionally auto-redirecting to OIDC and surfacing error/registered states.
     */
    @GET
    @Path("login")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response loginPage(
        @QueryParam("error")      final String error,
        @QueryParam("registered") @DefaultValue("false") final boolean registered,
        @CookieParam(LOCKOUT_COOKIE) final String lockoutCookie,
        @CookieParam(OidcUserProvisioner.ERROR_COOKIE) final String oidcErrorCookie) {
        // First run: no users exist yet. Send the deployer to the setup landing page to create the
        // initial local account, and short-circuit any OIDC auto-redirect below — the first account
        // must ALWAYS be local, even in a pure-OIDC deployment (PASSWORD_AUTH_ENABLED=false): that
        // initial administrator is the sysops break-glass credential should the IdP be unavailable.
        // During setup the local registration is always usable (both ENABLE_REGISTRATION and
        // PASSWORD_AUTH_ENABLED are ignored until a user exists).
        if (setupRequired()) {
            return Response.seeOther(URI.create("/welcome")).build();
        }
        // Auto-redirect to OIDC flow when configured, but not when there is an error or
        // success message to show (e.g. after registration or a failed OIDC attempt).
        if (oidcEnabled && oidcConfig.autoRedirect() && error == null && !registered) {
            return Response.seeOther(URI.create("/oidc-login")).build();
        }
        // error is null when absent, "" when present with no value (?error), or a string value.
        // Quarkus form auth redirects to /login?error (no value) on failure — treat key presence as truthy.
        // ?error=oidc is set by quarkus.oidc.authentication.error-path and shown separately.
        //
        // When the IdP itself denies access (e.g. Authelia access-control rule), it appends its own
        // query params to the redirect_uri. Quarkus then forwards everything to the error-path,
        // producing a double-? URL like /login?error=oidc?error=access_denied&... — redirect to clean.
        if (error != null && error.startsWith("oidc") && !"oidc".equals(error)) {
            return Response.seeOther(URI.create("/login?error=oidc")).build();
        }
        final boolean showOidcError = "oidc".equals(error);
        // The lockout cookie (set by doLogin on the failed form POST) marks this as a
        // lockout rather than a bad password; it takes precedence over the generic error banner. Its
        // value is the seconds left on the lockout, seeding the countdown.
        final boolean showLocked = lockoutCookie != null;
        final Duration lockoutRemaining = lockoutRemaining(lockoutCookie);
        final boolean showError = error != null && !"false".equals(error) && !showOidcError && !showLocked;
        // A refused OIDC login carries its reason code in the short-lived cookie set by OidcUserProvisioner (the code-flow failure redirect
        // cannot carry it); an unknown/absent code falls back to the generic banner text.
        final String oidcErrorMessage = OidcDenialReason.fromCode(oidcErrorCookie)
            .map(reason -> reason.message(oidcConfig.providerName()))
            .orElse("You are not authorized to access this application. Contact your administrator.");
        final Response.ResponseBuilder builder = Response.ok(loginTemplate
            .data("error", showError, "registered", registered, "theme", Theme.DEFAULT.value())
            .data("font", Font.DEFAULT.value())
            .data("locked", showLocked)
            .data("lockedMessage", LockoutMessages.retryMessage(lockoutRemaining))
            .data("oidcError", showOidcError)
            .data("oidcErrorMessage", oidcErrorMessage)
            .data("passwordAuthEnabled", passwordAuthConfig.enabled())
            .data("registrationEnabled", passwordAuthConfig.enabled() && registrationConfig.enabled())
            .data("oidcEnabled", oidcEnabled)
            .data("oidcProviderName", oidcConfig.providerName()))
            .type(MediaType.TEXT_HTML_TYPE);
        if (showOidcError) {
            // Clear the stale OIDC session cookie so the next "Log in with Authelia" click
            // starts a fresh code flow instead of retrying the same failed session.
            builder.cookie(new NewCookie.Builder("q_session").value("").path("/").maxAge(0).httpOnly(true).build());
            // Also drop any stale Settings connect-intent marker so a later ordinary login is not misread as a link attempt.
            builder.cookie(new NewCookie.Builder(OidcUserProvisioner.LINK_COOKIE).value("").path("/").maxAge(0).httpOnly(true).build());
            if (oidcErrorCookie != null) {
                // One-shot: the reason banner is rendered now, so a later reload shows the generic text.
                builder.cookie(new NewCookie.Builder(OidcUserProvisioner.ERROR_COOKIE).value("").path("/").maxAge(0).httpOnly(true).build());
            }
        }
        if (showLocked) {
            // One-shot: clear it so a later reload of the login page shows the normal form.
            builder.cookie(new NewCookie.Builder(LOCKOUT_COOKIE)
                .value("").path("/").maxAge(0).build());
            // The login form posts via fetch (data-ajax-submit) and never renders this HTML, so app.js
            // reads the seconds left from this header and runs a live countdown in the banner.
            builder.header(LOCKOUT_RETRY_AFTER_HEADER, Math.max(1L, lockoutRemaining.toSeconds()));
        }
        return builder.build();
    }

    private Duration lockoutRemaining(@Nullable final String lockoutCookie) {
        if (lockoutCookie != null) {
            try {
                final long seconds = Long.parseLong(lockoutCookie.strip());
                if (seconds > 0L) {
                    return Duration.ofSeconds(seconds);
                }
            } catch (final NumberFormatException e) {
                LOGGER.debug("Malformed lockout cookie value: {}", lockoutCookie);
            }
        }
        return ipThrottleConfig.lockoutDuration();
    }

    /**
     * Handles the login form submission: verifies the credentials and, on success, creates a server-side session and sets the {@code diurnal_session}
     * cookie before redirecting to the dashboard. A bad password redirects to {@code /login?error=true}; a lockout redirects to {@code /login}
     * carrying the short-lived lockout cookie so the page can show the countdown. The form posts via {@code fetch} (app.js), which follows the
     * redirect and reads the final URL (and the {@code X-Lockout-Retry-After} header on the login page) to tell success, error and lockout apart.
     */
    @POST
    @Path("login")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response doLogin(
        @FormParam("email") @Nullable final String email,
        @FormParam("password") @Nullable final String password) {
        final String clientIp = ClientAddress.of(routingContext);
        final Instant now = clock.now();
        final LoginResult result = authenticationService.authenticate(
            email == null ? "" : email, password == null ? "" : password, clientIp, now);

        return switch (result) {
            case LoginResult.Success success -> {
                final String token = sessionStore.create(
                    success.user(), Session.AUTH_SOURCE_PASSWORD, userAgent(), clientIp, now);
                yield Response.seeOther(URI.create("/")).cookie(sessionCookie(token)).build();
            }
            case LoginResult.LockedOut locked -> Response.seeOther(URI.create("/login"))
                    .cookie(lockoutCookie(locked.remaining()))
                    .build();
            case LoginResult.InvalidCredentials ignored -> Response.seeOther(URI.create("/login?error=true")).build();
        };
    }

    private NewCookie sessionCookie(final String token) {
        return new NewCookie.Builder(sessionConfig.cookieName())
                .value(token)
                .path("/")
                .httpOnly(true)
                .sameSite(NewCookie.SameSite.STRICT)
                .secure(isSecureRequest())
                .maxAge((int) sessionConfig.absoluteTimeout().toSeconds())
                .build();
    }

    private NewCookie lockoutCookie(final Duration remaining) {
        final long seconds = Math.max(1L, remaining.toSeconds());
        return new NewCookie.Builder(LOCKOUT_COOKIE)
                .value(Long.toString(seconds))
                .path("/")
                .httpOnly(true)
                .maxAge(LOCKOUT_COOKIE_MAX_AGE_SECONDS)
                .build();
    }

    private @Nullable String userAgent() {
        return routingContext == null ? null : routingContext.request().getHeader("User-Agent");
    }

    private boolean isSecureRequest() {
        return routingContext != null && routingContext.request().isSSL();
    }

    /**
     * Entry point for the OIDC code flow; authenticated users are forwarded home.
     */
    @GET
    @Path("oidc-login")
    @RolesAllowed(Role.Values.USER)
    public Response oidcLogin() {
        // Unauthenticated requests never reach here — the oidc-trigger permission policy
        // intercepts them first and issues the OIDC Authorization Code challenge.
        // Authenticated users (e.g. navigating from browser history) are forwarded home.
        return Response.seeOther(URI.create("/")).build();
    }

    /**
     * Handles the OIDC redirect-back, records the login, and forwards the user to the dashboard.
     */
    @GET
    @Path("oauth2/callback/oidc")
    @PermitAll
    @Transactional
    public Response oidcCallback(@CookieParam(OidcUserProvisioner.LINK_COOKIE) @Nullable final String linkIntent) {
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
            user, Session.AUTH_SOURCE_OIDC, userAgent(), ClientAddress.of(routingContext), clock.now());
        if (linkIntent != null) {
            // A Settings "Connect" round trip: the link itself was applied during authentication (OidcUserProvisioner + OidcLinkPolicy);
            // clear the one-shot intent marker and land back on Settings with a success banner instead of the dashboard.
            final NewCookie clearIntent =
                new NewCookie.Builder(OidcUserProvisioner.LINK_COOKIE).value("").path("/").maxAge(0).httpOnly(true).build();
            return Response.seeOther(URI.create("/settings?msg=" + MSG_OIDC_CONNECTED)).cookie(sessionCookie(token), clearIntent).build();
        }
        return Response.seeOther(URI.create("/")).cookie(sessionCookie(token)).build();
    }

    // ── First-run setup ──────────────────────────────────────────────────────

    /**
     * First-run landing page: introduces the application and guides the deployer to register the initial (local, administrator) account. Once any
     * user exists it redirects to {@code /login}, so it is only ever visible during setup. Deliberately independent of {@code PASSWORD_AUTH_ENABLED}:
     * the initial account is always created locally — in a pure-OIDC deployment it is the sysops break-glass administrator (its password becomes
     * usable by re-enabling password auth). The page content is identical in both modes — the deployer configured the auth mode and owns that
     * context.
     */
    @GET
    @Path("welcome")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response welcomePage() {
        if (!setupRequired()) {
            return Response.seeOther(URI.create("/login")).build();
        }
        return Response.ok(setupTemplate.data("theme", Theme.DEFAULT.value()).data("font", Font.DEFAULT.value())).build();
    }

    // ── Register ───────────────────────────────────────────────────────────

    /**
     * Renders the registration page. Returns {@code 404} only when password auth is disabled entirely (no local registration concept). When password
     * auth is on but registration is disabled and setup is complete, it still renders the page — showing a "registration disabled" banner instead of
     * the form, rather than a bare browser error.
     */
    @GET
    @Path("register")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response registerPage() {
        // During setup the page must render even with password auth disabled — the initial (break-glass) account is always created locally.
        if (!passwordAuthConfig.enabled() && !setupRequired()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (registrationNotAllowed()) {
            return Response.ok(renderRegisterDisabled()).build();
        }
        return Response.ok(renderRegister("", "", List.of(), List.of())).build();
    }

    /**
     * Handles the registration form submission: creates the user, mints a server-side session and sets the {@code diurnal_session} cookie so the new
     * account is logged straight in and redirected to the dashboard.
     */
    @POST
    @Path("register")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response register(
        @FormParam("email")           final String email,
        @FormParam("displayName")     final String displayName,
        @FormParam("password")        final String password,
        @FormParam("confirmPassword") final String confirmPassword) {

        // Mirrors registerPage: setup always permits creating the initial (break-glass) account locally.
        if (!passwordAuthConfig.enabled() && !setupRequired()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (registrationNotAllowed()) {
            return Response.status(Response.Status.FORBIDDEN).entity(renderRegisterDisabled()).build();
        }

        // Null-safe copies so the form can be re-rendered with the user's input preserved on failure.
        // The password fields are deliberately NOT preserved (never re-echoed into the HTML).
        final String emailValue = email == null ? "" : email;
        final String displayNameValue = displayName == null ? "" : displayName;

        // The web form collects a confirmPassword (normalised to blank so an empty field is reported as
        // missing rather than skipped); everything else — throttle, validation, duplicate check, account
        // creation — is the shared RegistrationService the API also calls, so the rules cannot diverge.
        final Instant now = clock.now();
        final RegistrationResult result = registrationService.register(
            email, displayName, password, confirmPassword == null ? "" : confirmPassword,
            ClientAddress.of(routingContext), now);

        return switch (result) {
            case RegistrationResult.Success success -> {
                final String token = sessionStore.create(
                    success.user(), Session.AUTH_SOURCE_PASSWORD, userAgent(), ClientAddress.of(routingContext), now);
                yield Response.seeOther(URI.create("/")).cookie(sessionCookie(token)).build();
            }
            case RegistrationResult.LockedOut locked -> {
                // The form posts via fetch (data-ajax-errors), so app.js reads the exact seconds from this
                // header and runs a live mm:ss countdown; the rendered banner (exact-seconds message) is
                // the no-JS fallback shown by a native form submit.
                yield Response.status(Response.Status.TOO_MANY_REQUESTS)
                        .header(LOCKOUT_RETRY_AFTER_HEADER, Math.max(1L, locked.remaining().toSeconds()))
                        .entity(renderRegister(emailValue, displayNameValue, List.of(),
                        List.of(LockoutMessages.retryMessage(locked.remaining()))))
                        .build();
            }
            case RegistrationResult.Invalid invalid -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(renderRegister(emailValue, displayNameValue, invalid.missingFields(), invalid.errors()))
                    .build();
            case RegistrationResult.DuplicateEmail ignored -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(renderRegister(emailValue, displayNameValue, List.of(), List.of("That email is already registered.")))
                    .build();
        };
    }

    private TemplateInstance renderRegister(final String email, final String displayName,
        final List<String> missingFields, final List<String> errors) {
        return registerTemplate
                .data("email", email)
                .data("displayName", displayName)
                .data("missingFields", missingFields)
                .data("errors", errors)
                .data("setup", setupRequired())
                .data("registrationDisabled", false)
                .data("theme", Theme.DEFAULT.value())
                .data("font", Font.DEFAULT.value())
                .data("maxPasswordLength", PasswordConstraints.MAX_LENGTH)
                .data("passwordConstraints", PasswordConstraints.all());
    }

    private TemplateInstance renderRegisterDisabled() {
        return registerTemplate
                .data("registrationDisabled", true)
                .data("setup", false)
                .data("theme", Theme.DEFAULT.value())
                .data("font", Font.DEFAULT.value());
    }

    private boolean setupRequired() {
        return User.count() == 0;
    }

    private boolean registrationNotAllowed() {
        // Setup overrides everything: the initial (break-glass) account is always created locally.
        return !setupRequired() && (!passwordAuthConfig.enabled() || !registrationConfig.enabled());
    }

    // ── Logout ────────────────────────────────────────────────────────────

    /**
     * Revokes the current server-side session, clears the session cookies, and redirects to the IdP logout (OIDC users) or {@code /login}.
     */
    @POST
    @Path("logout")
    public Response logout(
        @CookieParam("diurnal_session") @Nullable final String sessionToken,
        @CookieParam("q_session") @Nullable final String oidcSession) {
        // Revoke only this device's session; any other devices stay logged in. Resolve the owning
        // user first so the logout can be logged with the same identity detail as the login entry.
        Optional<User> sessionUser = Optional.empty();
        if (sessionToken != null && !sessionToken.isBlank()) {
            sessionUser = sessionStore.resolve(sessionToken, clock.now());
            sessionStore.revoke(sessionToken);
        }
        final NewCookie clearForm    = new NewCookie.Builder(sessionConfig.cookieName()).value("").path("/").maxAge(0).httpOnly(true).build();
        final NewCookie clearOidc    = new NewCookie.Builder("q_session").value("").path("/").maxAge(0).httpOnly(true).build();
        // RP-initiated logout: only redirect to the IdP if the user authenticated via OIDC
        // (has a q_session cookie). Password users go straight to /login.
        // We send id_token_hint so Authelia can identify and properly terminate the IdP session.
        // Without it, Authelia accepts the end_session request but does nothing.
        final boolean hasOidcSession = oidcSession != null && !oidcSession.isBlank();
        final URI target = (hasOidcSession ? oidcConfig.logoutUrl().filter(url -> !url.isBlank()) : Optional.<String>empty())
            .map(URI::create)
            .orElse(URI.create("/login"));
        sessionUser.ifPresentOrElse(
            user -> LOGGER.debug("Logout: revoking session for name={} email={} role={}, redirecting to {}",
            user.displayName, user.email, user.role, target),
            () -> LOGGER.debug("Logout: revoking session and redirecting to {}", target));
        return Response.seeOther(target).cookie(clearForm, clearOidc).build();
    }

    // ── Settings ───────────────────────────────────────────────────────────

    /**
     * Renders the settings page for the current user. The one-shot {@code msg} query parameter (set by the redirect-based account-link actions
     * below and by the OIDC connect callback) selects a status banner; unknown values render nothing.
     *
     * @param msg the status-banner code, when present
     * @return the rendered settings page
     */
    @GET
    @Path("settings")
    @RolesAllowed(Role.Values.USER)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance settingsPage(@QueryParam("msg") @Nullable final String msg) {
        final User user = currentUser.get();
        return settingsView(user, msg);
    }

    // ── Identity-provider connection (Settings → Account) ──────────────────

    /**
     * Starts the Settings "Connect {provider}" flow: sets the short-lived link-intent cookie and forwards into the OIDC code flow. The actual link
     * is applied during the callback's authentication ({@code OidcUserProvisioner} + {@code OidcLinkPolicy}), keyed on this cookie plus the
     * signed-in session — an email match alone can never link while password auth is enabled. Connecting is a one-way conversion: the account's
     * password is removed in the same step ({@code net.zodac.diurnal.auth.AccountLinkService#link}), so the confirm step in the Settings UI warns
     * about exactly that.
     *
     * <p>
     * Surface policy: the flow is a browser redirect dance with the identity provider, so it deliberately has no {@code /api/v1} twin — an API
     * client has no user agent to send through the code flow.
     */
    @POST
    @Path("internal/settings/oidc/connect")
    @RolesAllowed(Role.Values.USER)
    public Response connectOidc() {
        if (!oidcEnabled) {
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


    // ── Preferences ────────────────────────────────────────────────────────

    /**
     * The single settings endpoint: partially updates the current user's display name and/or preferences — only the form fields PRESENT in the
     * request change (PATCH semantics). The Settings page's controls each auto-save themselves on {@code change} by PATCHing here with just their
     * own field included, so every request deliberately carries one setting's data; a request may equally carry several. Every submitted value is
     * validated and an unrecognised one is rejected with {@code 422} (and the reason) so the client keeps the previous value — nothing is silently
     * coerced (a blank timezone is the explicit server-default reset). Every rule is the shared {@link ProfileService} the API's
     * {@code PATCH /api/v1/users/me} also calls. Returns {@code 204} when everything applied.
     *
     * @param displayName      the new display name, when submitted
     * @param theme            the new theme, when submitted
     * @param font             the new font, when submitted
     * @param calendarView     the new dashboard calendar style, when submitted
     * @param timezone         the new IANA timezone, when submitted
     * @param pageSize         the new list page size, when submitted
     * @param decimalPlaces    the new decimal-place count, when submitted
     * @param showStatsSummary the stats-summary checkbox values (hidden {@code "false"} + ticked {@code "true"}), when submitted
     * @param statsOrder       every "Action stats" field key in the arranged order, when submitted
     * @param statsEnabled     the ticked "Action stats" field keys, when submitted alongside {@code statsOrder}
     * @return {@code 204} on success, {@code 422} with the reason when a submitted value is rejected
     */
    @PATCH
    @Path("internal/settings")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateSettings(
        @FormParam("displayName") @Nullable final String displayName,
        @FormParam("theme") @Nullable final String theme,
        @FormParam("font") @Nullable final String font,
        @FormParam("calendarView") @Nullable final String calendarView,
        @FormParam("timezone") @Nullable final String timezone,
        @FormParam("pageSize") @Nullable final String pageSize,
        @FormParam("decimalPlaces") @Nullable final String decimalPlaces,
        @FormParam("showStatsSummary") @Nullable final List<String> showStatsSummary,
        @FormParam("statsOrder") @Nullable final List<String> statsOrder,
        @FormParam("statsEnabled") @Nullable final List<String> statsEnabled) {
        final User user = currentUser.get();

        ProfileResult result = new ProfileResult.Updated();
        if (displayName != null) {
            result = profileService.updateDisplayName(user, displayName);
        }
        if (theme != null && !(result instanceof ProfileResult.Invalid)) {
            result = profileService.updateTheme(user, theme);
        }
        if (font != null && !(result instanceof ProfileResult.Invalid)) {
            result = profileService.updateFont(user, font);
        }
        if (calendarView != null && !(result instanceof ProfileResult.Invalid)) {
            result = profileService.updateCalendarView(user, calendarView);
        }
        if (timezone != null && !(result instanceof ProfileResult.Invalid)) {
            result = profileService.updateTimezone(user, timezone);
        }
        if (pageSize != null && !(result instanceof ProfileResult.Invalid)) {
            result = profileService.updatePageSize(user, pageSize);
        }
        if (decimalPlaces != null && !(result instanceof ProfileResult.Invalid)) {
            result = profileService.updateDecimalPlaces(user, decimalPlaces);
        }
        // The checkbox posts a hidden "false" plus (when ticked) "true", so presence = any value and
        // the setting is on iff the values contain "true".
        if (showStatsSummary != null && !showStatsSummary.isEmpty() && !(result instanceof ProfileResult.Invalid)) {
            result = profileService.updateShowStatsSummary(user, showStatsSummary.contains("true"));
        }
        // The stats picker posts EVERY row's key in its (drag-arranged) DOM order as statsOrder, plus
        // the ticked subset as statsEnabled.
        if (statsOrder != null && !statsOrder.isEmpty() && !(result instanceof ProfileResult.Invalid)) {
            result = profileService.updateStatsFields(user, statsOrder, statsEnabled == null ? List.of() : statsEnabled);
        }

        return switch (result) {
            case ProfileResult.Updated ignored -> Response.noContent().build();
            case ProfileResult.Invalid invalid -> Response.status(422).entity(invalid.message()).build();
        };
    }

    /**
     * Changes the current (local) user's password. To defend against a hijacked session silently taking over the account, the caller must prove
     * knowledge of the existing password: the flow first asks for the {@code currentPassword}, then the new password entered and re-entered to
     * confirm ({@code newPassword} + {@code confirmPassword}). All three values arrive here. Returns {@code 422} when the current password does not
     * match (body {@link PasswordChangeService#CURRENT_PASSWORD_ERROR}) or when the new password is empty or the two copies do not match (body
     * {@link PasswordChangeService#NEW_PASSWORD_ERROR}). {@code 403} for an account holding no password (OIDC-only), and
     * {@code 200} once the new hash is persisted. The response body drives which step the client returns the user to.
     */
    @POST
    @Path("internal/settings/password")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updatePassword(
        @FormParam("currentPassword") final String currentPassword,
        @FormParam("newPassword")     final String newPassword,
        @FormParam("confirmPassword") final String confirmPassword,
        @CookieParam("diurnal_session") @Nullable final String sessionToken) {
        // The web form collects a confirmPassword (normalised to blank so an empty field is rejected);
        // everything else — the local-account guard, current-password proof, new-password rules, re-hash
        // and other-session revocation — is the shared PasswordChangeService the API also calls.
        final PasswordChangeResult result = passwordChangeService.change(currentUser.get(), currentPassword, newPassword,
            confirmPassword == null ? "" : confirmPassword, sessionToken, ClientAddress.of(routingContext));
        return switch (result) {
            case PasswordChangeResult.Success ignored -> Response.ok().build();
            case PasswordChangeResult.NotLocalAccount ignored -> Response.status(Response.Status.FORBIDDEN).build();
            case PasswordChangeResult.WrongCurrentPassword ignored ->
                Response.status(422).entity(PasswordChangeService.CURRENT_PASSWORD_ERROR).build();
            case PasswordChangeResult.InvalidNewPassword invalid -> Response.status(422).entity(invalid.message()).build();
        };
    }

    /**
     * Verifies the current (local) user's existing password without changing anything, so the settings client can confirm step 1 of the
     * password-change flow before asking for the new password. Returns {@code 204} when it matches, {@code 422} when it does not (or is empty, body
     * {@link PasswordChangeService#CURRENT_PASSWORD_ERROR}), and {@code 403} for an account holding no password (OIDC-only).
     * This is a UX aid only — {@link #updatePassword} re-verifies the current password authoritatively on the mutating request.
     *
     * <p>
     * Like {@link #updatePassword}, this applies <b>no</b> lockout: an already-authenticated user confirming their own password gets unlimited tries,
     * wholly separate from the per-IP login/registration lockout ({@code IpThrottle}) — a mismatch here never checks nor increments that shared
     * counter.
     *
     * @param currentPassword the password to check against the stored hash
     * @return the verification outcome as an empty response
     */
    @POST
    @Path("internal/settings/password/verify")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response verifyCurrentPassword(@FormParam("currentPassword") final String currentPassword) {
        return switch (passwordChangeService.verify(currentUser.get(), currentPassword, ClientAddress.of(routingContext))) {
            case PasswordChangeResult.Success ignored -> Response.noContent().build();
            case PasswordChangeResult.NotLocalAccount ignored -> Response.status(Response.Status.FORBIDDEN).build();
            case PasswordChangeResult.WrongCurrentPassword ignored ->
                Response.status(422).entity(PasswordChangeService.CURRENT_PASSWORD_ERROR).build();
            case PasswordChangeResult.InvalidNewPassword ignored -> Response.status(422).build();
        };
    }

    /**
     * Revokes every one of the current user's sessions — including the one making this request ("log out from everywhere") — then clears the session
     * cookies and redirects to {@code /login}, forcing a fresh login on every device.
     */
    @POST
    @Path("internal/settings/sessions/revoke-all")
    @RolesAllowed(Role.Values.USER)
    @Transactional
    public Response revokeAllSessions() {
        final User user = currentUser.get();
        sessionStore.revokeAllForUser(user.id);
        LOGGER.info("All sessions revoked for user: {} (log out from everywhere)", user.email);
        final NewCookie clearForm = new NewCookie.Builder(sessionConfig.cookieName()).value("").path("/").maxAge(0).httpOnly(true).build();
        final NewCookie clearOidc = new NewCookie.Builder("q_session").value("").path("/").maxAge(0).httpOnly(true).build();
        return Response.seeOther(URI.create("/login")).cookie(clearForm, clearOidc).build();
    }

    private TemplateInstance settingsView(final User user, @Nullable final String msg) {
        final String providerName = oidcConfig.providerName();
        // The one-shot status banner for the connect flow's redirect back: the success code, or a refused connect's OidcDenialReason code
        // (the provisioner sends link denials back HERE — the session is still valid, and bouncing to the login page read as a logout).
        // An unknown (or absent) code renders no banner.
        final String settingsMessage;
        final boolean settingsMessageIsError;
        if (MSG_OIDC_CONNECTED.equals(msg)) {
            settingsMessage = "Connected to " + providerName + ", and your password has been removed.";
            settingsMessageIsError = false;
        } else {
            settingsMessage = OidcDenialReason.fromCode(msg).map(reason -> reason.message(providerName)).orElse(null);
            settingsMessageIsError = settingsMessage != null;
        }
        return settingsTemplate
                .data("settingsMessage", settingsMessage)
                .data("settingsBannerVariant", settingsMessageIsError ? "error" : "success")
                .data("oidcEnabled", oidcEnabled)
                .data("oidcIssuerUrl", oidcIssuerUrl)
                .data("email", user.email)
                .data("displayName", user.displayName)
                // Any account HOLDING a password (in practice the break-glass administrator when password
                // login is off) can change it; OIDC-only accounts have none, so the field is hidden.
                // Deliberately independent of PASSWORD_AUTH_ENABLED — matches PasswordChangeService.
                .data("canChangePassword", user.passwordHash != null && !user.passwordHash.isBlank())
                // OIDC-only accounts have no password at all: they render no Password section (the
                // Identity Provider section states the connection) and no Connect button.
                .data("isOidcUser", user.oidcSubject != null && !user.oidcSubject.isBlank())
                .data("maxPasswordLength", PasswordConstraints.MAX_LENGTH)
                .data("passwordConstraints", PasswordConstraints.all())
                .data("oidcProviderName", oidcConfig.providerName())
                .data("theme", user.theme)
                .data("font", user.font)
                .data("isAdmin", user.isAdmin())
                .data("pageSize", user.pageSize)
                .data("pageSizeOptions", UserSettings.PAGE_SIZE_OPTIONS)
                .data("showStatsSummary", user.showStatsSummary)
                .data("decimalPlaces", user.decimalPlaces)
                .data("decimalPlacesOptions", UserSettings.DECIMAL_PLACES_OPTIONS)
                .data("themeOptions", Theme.values())
                .data("fontOptions", Font.values())
                .data("calendarView", user.calendarView)
                .data("calendarViewOptions", CalendarView.values())
                .data("statsFieldChoices", ActionStatField.choices(user.statsFields))
                .data("timezoneChoices",
                        UserSettings.timezoneChoices(clock.zone(), clock.now(), user.timezone));
    }

    // ── Dashboard (protected) ──────────────────────────────────────────────

    /**
     * Renders the dashboard with the user's calendar and three most-recent action stats. Each summary tile row shows the user's top three enabled
     * "Action stats" (the same display preference that drives the Stats page), in their chosen order.
     */
    @GET
    @Path("/")
    @RolesAllowed(Role.Values.USER)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dashboard() {
        final User user = currentUser.get();
        final List<?> recentStats = statsService.forMostRecent(user.id, 3);
        final List<ActionStatField> summaryFields = ActionStatField.displayFields(user.statsFields)
            .stream()
            .limit(3)
            .toList();
        return dashboardTemplate
                .data("email", user.email)
                .data("displayName", user.displayName)
                .data("theme", user.theme)
                .data("font", user.font)
                .data("isAdmin", user.isAdmin())
                .data("calendarView", user.calendarView)
                .data("today", clock.today(clock.zoneFor(user.timezone)).toString())
                .data("showStatsSummary", user.showStatsSummary)
                .data("decimalPlaces", user.decimalPlaces)
                .data("summaryFields", summaryFields)
                .data("recentStats", recentStats);
    }
}
