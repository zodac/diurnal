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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import net.zodac.diurnal.auth.AuthenticationService;
import net.zodac.diurnal.auth.ClientAddress;
import net.zodac.diurnal.auth.IpThrottle;
import net.zodac.diurnal.auth.LockoutMessages;
import net.zodac.diurnal.auth.LoginResult;
import net.zodac.diurnal.auth.PasswordConstraints;
import net.zodac.diurnal.auth.Passwords;
import net.zodac.diurnal.auth.RegistrationAttemptLog;
import net.zodac.diurnal.auth.RoleAssigner;
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
import net.zodac.diurnal.user.CurrentUser;
import net.zodac.diurnal.user.Role;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.user.UserSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

/**
 * Serves the top-level web UI pages: login, register, logout, settings and the dashboard.
 */
// This is the app's single web-page controller: many injected collaborators (templates, config,
// session + auth services) are inherent to that role rather than a design smell, so the field count
// rule is suppressed here in the same spirit as the wide User entity.
@Path("/")
@SuppressWarnings("PMD.TooManyFields")
public class WebResource {

    private static final Logger LOGGER = LogManager.getLogger(WebResource.class);

    // Password-change error bodies. Returned to (and matched by) the settings client so it can route the
    // user back to the correct step: a wrong current password → back to step 1; a mismatch → stay on the
    // confirmation step. Kept in sync with the checks in settings.html's password-change script.
    private static final String CURRENT_PASSWORD_ERROR = "Current password is incorrect";
    private static final String NEW_PASSWORD_ERROR = "Passwords do not match";
    private static final String NEW_PASSWORD_TOO_LONG_ERROR =
        "Password must be at most " + PasswordConstraints.MAX_LENGTH + " characters";

    // Carries the exact seconds left on a lockout to the AJAX form handlers (app.js), which post via fetch
    // and so never render the server-side banner — they run a live mm:ss countdown from this value instead.
    // Shared by both the login (GET /login render) and registration (POST /register 429) surfaces.
    private static final String LOCKOUT_RETRY_AFTER_HEADER = "X-Lockout-Retry-After";

    // Short-lived cookie signalling that a just-rejected form login was a lockout (not a bad password).
    // Its value is the seconds left; the GET /login render reads it to show the banner and seed the
    // countdown, then clears it. Only needs to survive the immediate redirect to the login page.
    private static final String LOCKOUT_COOKIE = "diurnal_login_lockout";
    private static final int LOCKOUT_COOKIE_MAX_AGE_SECONDS = 30;

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

    @Inject RoleAssigner roleAssigner;

    @Inject AppClock clock;

    @Inject AuthenticationService authenticationService;

    @Inject Passwords passwords;

    @Inject SessionStore sessionStore;

    @Inject SessionConfig sessionConfig;

    @Context
    @Nullable
    RoutingContext routingContext;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @Inject
    OidcConfig oidcConfig;

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @Inject
    RegistrationConfig registrationConfig;

    @Inject
    IpThrottleConfig ipThrottleConfig;

    @Inject
    IpThrottle ipThrottle;

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
        @CookieParam(LOCKOUT_COOKIE) final String lockoutCookie) {
        // First run: no users exist yet. Send the deployer to the setup landing page to create the
        // initial local account, and short-circuit any OIDC auto-redirect below — the first account
        // must be local. During set up the initial account can always be created (ENABLE_REGISTRATION
        // is ignored until a user exists), so this is gated only on password auth being enabled; a
        // pure-OIDC deployment (no local auth) falls through to the normal login/OIDC flow.
        if (setupRequired() && passwordAuthConfig.enabled()) {
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
        final Response.ResponseBuilder builder = Response.ok(loginTemplate
            .data("error", showError, "registered", registered, "theme", "system")
            .data("font", "nova")
            .data("locked", showLocked)
            .data("lockedMessage", LockoutMessages.retryMessage(lockoutRemaining))
            .data("oidcError", showOidcError)
            .data("passwordAuthEnabled", passwordAuthConfig.enabled())
            .data("registrationEnabled", passwordAuthConfig.enabled() && registrationConfig.enabled())
            .data("oidcEnabled", oidcEnabled)
            .data("oidcProviderName", oidcConfig.providerName()))
            .type(MediaType.TEXT_HTML_TYPE);
        if (showOidcError) {
            // Clear the stale OIDC session cookie so the next "Log in with Authelia" click
            // starts a fresh code flow instead of retrying the same failed session.
            builder.cookie(new NewCookie.Builder("q_session").value("").path("/").maxAge(0).httpOnly(true).build());
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
    public Response oidcCallback() {
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
        if (!identity.isAnonymous()) {
            final Optional<User> found = currentUser.find();
            if (found.isPresent()) {
                final User user = found.get();
                user.lastLoginAt = Instant.now();
                user.persist();
                LOGGER.debug("OIDC login: name={} email={} role={}", user.displayName, user.email, user.role);
                final String token = sessionStore.create(
                    user, Session.AUTH_SOURCE_OIDC, userAgent(), ClientAddress.of(routingContext), clock.now());
                return Response.seeOther(URI.create("/")).cookie(sessionCookie(token)).build();
            }
        }

        return Response.seeOther(URI.create("/")).build();
    }

    // ── First-run setup ──────────────────────────────────────────────────────

    /**
     * First-run landing page: introduces the application and guides the deployer to register the initial (local, administrator) account. Once any
     * user exists — or when local registration is unavailable — it redirects to {@code /login}, so it is only ever visible during setup.
     */
    @GET
    @Path("welcome")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public Response welcomePage() {
        if (!setupRequired() || !passwordAuthConfig.enabled()) {
            return Response.seeOther(URI.create("/login")).build();
        }
        return Response.ok(setupTemplate.data("theme", "system").data("font", "nova")).build();
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
        if (!passwordAuthConfig.enabled()) {
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

        if (!passwordAuthConfig.enabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (registrationNotAllowed()) {
            return Response.status(Response.Status.FORBIDDEN).entity(renderRegisterDisabled()).build();
        }

        // Null-safe copies so the form can be re-rendered with the user's input preserved on failure.
        // The password fields are deliberately NOT preserved (never re-echoed into the HTML).
        final String emailValue = email == null ? "" : email;
        final String displayNameValue = displayName == null ? "" : displayName;
        final String passwordValue = password == null ? "" : password;
        final String confirmValue = confirmPassword == null ? "" : confirmPassword;

        // Global per-IP lockout (the same counter failed logins feed). Revealed on the first attempt made
        // WHILE locked — the entry check here — not on the threshold-tripping one below; the banner rides
        // the same [data-form-errors] slot every other registration error uses.
        final String clientIp = ClientAddress.of(routingContext);
        final Instant now = clock.now();
        if (ipThrottle.isLocked(clientIp, now)) {
            LOGGER.debug("Throttled registration attempt (IP: {})", clientIp);
            final Duration remaining = ipThrottle.lockoutRemaining(clientIp, now);
            // The form posts via fetch (data-ajax-errors), so app.js reads the exact seconds from this
            // header and runs a live mm:ss countdown; the rendered banner (exact-seconds message) is the
            // no-JS fallback shown by a native form submit.
            final String lockedMessage = LockoutMessages.retryMessage(remaining);
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .header(LOCKOUT_RETRY_AFTER_HEADER, Math.max(1L, remaining.toSeconds()))
                    .entity(renderRegister(emailValue, displayNameValue, List.of(), List.of(lockedMessage)))
                    .build();
        }

        final List<String> missingFields = missingFields(emailValue, displayNameValue, passwordValue, confirmValue);
        final List<String> errors = validateRegistration(emailValue, passwordValue, confirmValue);
        final String normalised = emailValue.toLowerCase(Locale.ROOT).strip();
        if (missingFields.isEmpty() && errors.isEmpty() && User.findByEmail(normalised).isPresent()) {
            errors.add("That email is already registered.");
        }
        if (!missingFields.isEmpty() || !errors.isEmpty()) {
            // Record the rejected attempt against the IP throttle and log it (running count + a WARN if
            // this failure tripped the lockout), mirroring the shared failed-login logging.
            RegistrationAttemptLog.logFailure(LOGGER, ipThrottle.recordFailure(clientIp, now), emailValue, clientIp);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(renderRegister(emailValue, displayNameValue, missingFields, errors))
                    .build();
        }

        final User user = new User();
        user.email = normalised;
        user.displayName = displayNameValue.strip();
        user.passwordHash = passwords.hash(passwordValue);
        user.role = roleAssigner.roleForNewUser();
        user.lastLoginAt = Instant.now();
        user.persist();

        LOGGER.info("New user registered: {} (role={})", normalised, user.role);
        final String token = sessionStore.create(
            user, Session.AUTH_SOURCE_PASSWORD, userAgent(), ClientAddress.of(routingContext), now);
        return Response.seeOther(URI.create("/")).cookie(sessionCookie(token)).build();
    }

    private static List<String> missingFields(final String email, final String displayName,
        final String password, final String confirmPassword) {
        final List<String> missing = new ArrayList<>();
        if (email.isBlank()) {
            missing.add("Email");
        }
        if (displayName.isBlank()) {
            missing.add("Display name");
        }
        if (password.isEmpty()) {
            missing.add("Password");
        }
        if (confirmPassword.isEmpty()) {
            missing.add("Confirm password");
        }
        return missing;
    }

    private static List<String> validateRegistration(final String email, final String password,
        final String confirmPassword) {
        final List<String> errors = new ArrayList<>();

        if (!email.isBlank() && !email.contains("@")) {
            errors.add("Email must contain an @ symbol.");
        }

        if (password.length() > PasswordConstraints.MAX_LENGTH) {
            errors.add("Password must be at most " + PasswordConstraints.MAX_LENGTH + " characters.");
        }

        if (!password.isEmpty() && !confirmPassword.isEmpty() && !password.equals(confirmPassword)) {
            errors.add("The passwords did not match.");
        }

        return errors;
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
                .data("theme", "system")
                .data("font", "nova")
                .data("maxPasswordLength", PasswordConstraints.MAX_LENGTH)
                .data("passwordConstraints", PasswordConstraints.all());
    }

    private TemplateInstance renderRegisterDisabled() {
        return registerTemplate
                .data("registrationDisabled", true)
                .data("setup", false)
                .data("theme", "system")
                .data("font", "nova");
    }

    private boolean setupRequired() {
        return User.count() == 0;
    }

    private boolean registrationNotAllowed() {
        return !passwordAuthConfig.enabled() || (!setupRequired() && !registrationConfig.enabled());
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
     * Renders the settings page for the current user.
     */
    @GET
    @Path("settings")
    @RolesAllowed(Role.Values.USER)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance settingsPage() {
        final User user = currentUser.get();
        return settingsView(user);
    }

    // ── Preferences ────────────────────────────────────────────────────────
    //
    // Each preference is its own PATCH endpoint so updating one never touches the others (a partial
    // update, not a whole-object replace). The client posts only the changed control on its `change`
    // event and shows the saved indicator via {@code htmx:afterRequest}; every endpoint returns
    // {@code 204}. All values are sanitised against their allow-list, so an unoffered/absent value
    // falls back to the setting's default (timezone → server default).

    /**
     * Updates the current user's theme to the sanitised {@code theme}. Returns {@code 204}.
     */
    @PATCH
    @Path("settings/theme")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateTheme(@FormParam("theme") final String theme) {
        final String value = UserSettings.sanitiseTheme(theme);
        return updateSetting("Theme", value, user -> user.theme = value);
    }

    /**
     * Updates the current user's font to the sanitised {@code font}. Returns {@code 204}.
     */
    @PATCH
    @Path("settings/font")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateFont(@FormParam("font") final String font) {
        final String value = UserSettings.sanitiseFont(font);
        return updateSetting("Font", value, user -> user.font = value);
    }

    /**
     * Updates the current user's calendar view to the sanitised {@code calendarView}. Returns {@code 204}.
     */
    @PATCH
    @Path("settings/calendar-view")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateCalendarView(@FormParam("calendarView") final String calendarView) {
        final String value = UserSettings.sanitiseCalendarView(calendarView);
        return updateSetting("Calendar view", value, user -> user.calendarView = value);
    }

    /**
     * Updates the current user's timezone to the sanitised {@code timezone} (blank or unoffered → server default). Returns {@code 204}.
     */
    @PATCH
    @Path("settings/timezone")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateTimezone(@FormParam("timezone") final String timezone) {
        final String value = UserSettings.sanitiseTimezone(timezone);
        return updateSetting("Timezone", value, user -> user.timezone = value);
    }

    /**
     * Updates the current user's page size. Unlike the other preferences, an out-of-range or non-numeric value is rejected with {@code 422} (and the
     * {@link UserSettings#PAGE_SIZE_RANGE_MESSAGE} body) rather than coerced, so the client can show an error and keep the previous value. A valid
     * value persists and returns {@code 204}.
     */
    @PATCH
    @Path("settings/page-size")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updatePageSize(@FormParam("pageSize") final String pageSize) {
        final Integer parsed = UserSettings.parsePageSize(pageSize);
        if (parsed == null) {
            return Response.status(422).entity(UserSettings.PAGE_SIZE_RANGE_MESSAGE).build();
        }
        final int value = parsed;
        return updateSetting("Page size", value, user -> user.pageSize = value);
    }

    /**
     * Updates the current user's decimal-place preference. Like the page size, an out-of-range or non-numeric value is rejected with {@code 422} (and
     * the {@link UserSettings#DECIMAL_PLACES_RANGE_MESSAGE} body) rather than coerced, so the client can show an error and keep the previous value. A
     * valid value persists and returns {@code 204}.
     */
    @PATCH
    @Path("settings/decimal-places")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateDecimalPlaces(@FormParam("decimalPlaces") final String decimalPlaces) {
        final Integer parsed = UserSettings.parseDecimalPlaces(decimalPlaces);
        if (parsed == null) {
            return Response.status(422).entity(UserSettings.DECIMAL_PLACES_RANGE_MESSAGE).build();
        }
        final int value = parsed;
        return updateSetting("Decimal places", value, user -> user.decimalPlaces = value);
    }

    /**
     * Updates which per-action stats show on the Stats page, and in what order. The client posts EVERY row's key in its (drag-arranged) DOM order as
     * {@code statsOrder}, plus the ticked subset as {@code statsEnabled}; these are encoded into the stored arrangement (disabled fields kept in
     * place, {@code last-performed} forced enabled). A display-only preference — it never affects how statistics are computed. Returns {@code 204}.
     */
    @PATCH
    @Path("settings/stats-fields")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateStatsFields(
        @FormParam("statsOrder")   final List<String> statsOrder,
        @FormParam("statsEnabled") final List<String> statsEnabled) {
        final List<String> order = statsOrder == null ? List.of() : statsOrder;
        final List<String> enabled = statsEnabled == null ? List.of() : statsEnabled;
        final String value = "order=" + order + " enabled=" + enabled;
        return updateSetting("Action stats", value, user -> user.statsFields = ActionStatField.encode(order, enabled));
    }

    /**
     * Toggles whether the dashboard shows the stats-summary strip. The checkbox posts a hidden {@code "false"} plus (when ticked) {@code "true"}, so
     * the setting is on iff the values contain {@code "true"}. Returns {@code 204}.
     */
    @PATCH
    @Path("settings/show-stats-summary")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateShowStatsSummary(@FormParam("showStatsSummary") final List<String> showStatsSummary) {
        final boolean show = showStatsSummary != null && showStatsSummary.contains("true");
        return updateSetting("Show stats summary", show, user -> user.showStatsSummary = show);
    }

    private Response updateSetting(final String settingName, final @Nullable Object newValue, final Consumer<User> mutator) {
        final User user = currentUser.get();
        mutator.accept(user);
        user.persist();
        LOGGER.debug("Setting '{}' changed to '{}' for user {}", settingName, newValue, user.email);
        return Response.noContent().build();
    }

    /**
     * Updates the current user's display name, returning {@code 422} if it is blank.
     */
    @POST
    @Path("settings/display-name")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateDisplayName(@FormParam("displayName") final String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return Response.status(422).build();
        }
        final User user = currentUser.get();
        user.displayName = displayName.strip();
        user.persist();
        LOGGER.info("Display name changed to '{}' for user {}", user.displayName, user.email);
        return Response.ok().build();
    }

    /**
     * Changes the current (local) user's password. To defend against a hijacked session silently taking over the account, the caller must prove
     * knowledge of the existing password: the flow first asks for the {@code currentPassword}, then the new password entered and re-entered to
     * confirm ({@code newPassword} + {@code confirmPassword}). All three values arrive here. Returns {@code 422} when the current password does not
     * match (body {@link #CURRENT_PASSWORD_ERROR}) or when the new password is empty or the two copies do not match (body
     * {@link #NEW_PASSWORD_ERROR}). {@code 403} for a non-local (OIDC-only) account or when password auth is disabled, and {@code 200} once the new
     * hash is persisted. The response body drives which step the client returns the user to.
     */
    @POST
    @Path("settings/password")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updatePassword(
        @FormParam("currentPassword") final String currentPassword,
        @FormParam("newPassword")     final String newPassword,
        @FormParam("confirmPassword") final String confirmPassword,
        @CookieParam("diurnal_session") @Nullable final String sessionToken) {
        final User user = currentUser.get();
        // Only local accounts have a password to change; OIDC-only users (and deployments with password
        // auth switched off entirely) have none. The UI already hides the field for them — this guards
        // the endpoint directly.
        if (!passwordAuthConfig.enabled() || user.passwordHash == null || user.passwordHash.isBlank()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        // The caller must know the current password — this is what stops a hijacked session from silently
        // resetting it. Checked before the new-password rules so a wrong current password is reported (and
        // the client sent back to the first step) regardless of the new/confirm values. The client also
        // verifies this up front via verifyCurrentPassword, but that is only a UX aid: this is the
        // authoritative check on the mutating request.
        //
        // NO LOCKOUT HERE (deliberate): this is an already-authenticated user changing their OWN password,
        // so they get unlimited attempts. It is entirely separate from the per-IP login/registration
        // lockout (IpThrottle) — a wrong current password neither consults that shared counter nor feeds
        // it (it never calls recordFailure), so failed password changes can never lock the IP out of
        // logging in or registering, and vice versa. The WARN below is an audit trail only, not a throttle.
        if (currentPasswordMismatch(user.passwordHash, currentPassword)) {
            LOGGER.warn("Failed current-password check on password change for user: {} (IP: {})",
                user.email, ClientAddress.of(routingContext));
            return Response.status(422).entity(CURRENT_PASSWORD_ERROR).build();
        }
        // The new password cannot be empty, and the re-entered confirmation must match.
        if (newPassword == null || newPassword.isEmpty() || !newPassword.equals(confirmPassword)) {
            return Response.status(422).entity(NEW_PASSWORD_ERROR).build();
        }
        // Cap the length to bound the hashing cost (an over-long input is a cheap CPU-exhaustion lever) —
        // mirrors the registration guard so the new password is capped identically however the account
        // was created.
        if (newPassword.length() > PasswordConstraints.MAX_LENGTH) {
            return Response.status(422).entity(NEW_PASSWORD_TOO_LONG_ERROR).build();
        }
        user.passwordHash = passwords.hash(newPassword);
        user.persist();
        // A password change evicts every other device (a common response to suspected compromise) while
        // keeping the session that made the change signed in, so the user is not logged out mid-action.
        if (sessionToken != null && !sessionToken.isBlank()) {
            sessionStore.revokeOthersForUser(user.id, sessionToken);
        }
        LOGGER.info("Password changed for user: {} (other sessions revoked)", user.email);
        return Response.ok().build();
    }

    /**
     * Verifies the current (local) user's existing password without changing anything, so the settings client can confirm step 1 of the
     * password-change flow before asking for the new password. Returns {@code 204} when it matches, {@code 422} when it does not (or is empty, body
     * {@link #CURRENT_PASSWORD_ERROR}), and {@code 403} for a non-local (OIDC-only) account or when password auth is disabled. This is a UX aid only
     * — {@link #updatePassword} re-verifies the current password authoritatively on the mutating request.
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
    @Path("settings/password/verify")
    @RolesAllowed(Role.Values.USER)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response verifyCurrentPassword(@FormParam("currentPassword") final String currentPassword) {
        final User user = currentUser.get();
        // Only local accounts have a password to verify; mirror updatePassword's guard exactly.
        if (!passwordAuthConfig.enabled() || user.passwordHash == null || user.passwordHash.isBlank()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (currentPasswordMismatch(user.passwordHash, currentPassword)) {
            LOGGER.warn("Failed current-password check on password-change verify for user: {} (IP: {})",
                user.email, ClientAddress.of(routingContext));
            return Response.status(422).entity(CURRENT_PASSWORD_ERROR).build();
        }
        return Response.noContent().build();
    }

    private boolean currentPasswordMismatch(final String passwordHash, final String currentPassword) {
        return currentPassword == null || currentPassword.isEmpty()
                || !passwords.matches(currentPassword, passwordHash);
    }

    /**
     * Revokes every one of the current user's sessions — including the one making this request ("log out from everywhere") — then clears the session
     * cookies and redirects to {@code /login}, forcing a fresh login on every device.
     */
    @POST
    @Path("settings/sessions/revoke-all")
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

    private TemplateInstance settingsView(final User user) {
        return settingsTemplate
                .data("email", user.email)
                .data("displayName", user.displayName)
                // Local accounts (a password hash present) can change their password; OIDC-only accounts
                // — and any deployment with password auth disabled — cannot, so the field is hidden.
                .data("canChangePassword",
                        passwordAuthConfig.enabled() && user.passwordHash != null && !user.passwordHash.isBlank())
                // OIDC-only accounts have no password at all; instead of the password field the Account
                // card shows a note that their authentication is managed by the identity provider.
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
                .data("themeOptions", UserSettings.THEME_OPTIONS)
                .data("fontOptions", UserSettings.FONT_OPTIONS)
                .data("calendarView", user.calendarView)
                .data("calendarViewOptions", UserSettings.CALENDAR_VIEW_OPTIONS)
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
