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

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.i18n.MessageBundles;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
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
import java.util.Locale;
import java.util.Optional;
import net.zodac.diurnal.auth.lockout.IpThrottleConfig;
import net.zodac.diurnal.auth.lockout.LockoutMessages;
import net.zodac.diurnal.auth.oidc.OidcConfig;
import net.zodac.diurnal.auth.oidc.OidcUserProvisioner;
import net.zodac.diurnal.auth.oidc.QuarkusOidcConfig;
import net.zodac.diurnal.auth.session.Session;
import net.zodac.diurnal.auth.session.SessionCookies;
import net.zodac.diurnal.auth.session.SessionStore;
import net.zodac.diurnal.http.ClientAddress;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.Font;
import net.zodac.diurnal.user.Language;
import net.zodac.diurnal.user.Theme;
import net.zodac.diurnal.user.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.Nullable;

/**
 * The web UI's authentication pages: login, first-run setup, registration and logout. The API twin of these capabilities is {@link AuthResource}
 * ({@code /api/v1/auth}), and both surfaces run the same {@link AuthenticationService}/{@link RegistrationService} rules — only the medium differs
 * (a redirect and a rendered banner here, JSON and a status code there).
 */
@Path("/")
public class AuthWebResource {

    private static final Logger LOGGER = LogManager.getLogger(AuthWebResource.class);

    // Carries the exact seconds left on a lockout to the AJAX form handlers (app.js), which post via fetch
    // and so never render the server-side banner — they run a live mm:ss countdown from this value instead.
    // Shared by both the login (GET /login render) and registration (POST /register 429) surfaces.
    private static final String LOCKOUT_RETRY_AFTER_HEADER = "X-Lockout-Retry-After";

    // Short-lived cookie signalling that a just-rejected form login was a lockout (not a bad password).
    // Its value is the seconds left; the GET /login render reads it to show the banner and seed the
    // countdown, then clears it. Only needs to survive the immediate redirect to the login page.
    private static final String LOCKOUT_COOKIE = "diurnal_login_lockout";
    private static final int LOCKOUT_COOKIE_MAX_AGE_SECONDS = 30;

    private final Template loginTemplate;
    private final Template registerTemplate;
    private final Template setupTemplate;
    private final Template oidcMessagesTemplate;
    private final Template textFailureMessageTemplate;
    private final Template passwordRejectionTemplate;
    private final AppClock clock;
    private final AuthenticationService authenticationService;
    private final RegistrationService registrationService;
    private final SessionStore sessionStore;
    private final SessionCookies sessionCookies;
    private final QuarkusOidcConfig quarkusOidcConfig;
    private final OidcConfig oidcConfig;
    private final PasswordAuthConfig passwordAuthConfig;
    private final RegistrationConfig registrationConfig;
    private final IpThrottleConfig ipThrottleConfig;

    /**
     * Injects the page templates, the shared authentication and registration services, the session store and cookie builder, and every config view
     * the authentication pages read.
     *
     * @param loginTemplate the login page template
     * @param registerTemplate the register page template
     * @param setupTemplate the first-run setup page template
     * @param oidcMessagesTemplate the translated OIDC connect/denial banner partial template
     * @param textFailureMessageTemplate the shared text-validation-pipeline rejection message partial template
     * @param passwordRejectionTemplate the translated password-mismatch/unchanged banner partial template
     * @param clock the application clock for date-boundary logic
     * @param authenticationService the shared credential-verification service
     * @param registrationService the shared registration service
     * @param sessionStore the session store used to mint and revoke session tokens
     * @param sessionCookies the shared session-cookie builder
     * @param quarkusOidcConfig the framework-owned {@code quarkus.oidc.*} keys the page reads (tenant-enabled, the IdP base URL)
     * @param oidcConfig the application OIDC policy settings
     * @param passwordAuthConfig the password-auth settings
     * @param registrationConfig the registration settings
     * @param ipThrottleConfig the per-IP throttle settings
     */
    // Constructor injection is what CODE_STYLE.md mandates, and it explicitly keeps the parameter-count limits off, so the collaborator count here
    // is the convention rather than a smell.
    @SuppressWarnings("OverlyCoupledMethod")
    @Inject
    public AuthWebResource(@Location("login") final Template loginTemplate, @Location("register") final Template registerTemplate,
        @Location("setup") final Template setupTemplate, @Location("partials/oidc-messages") final Template oidcMessagesTemplate,
        @Location("partials/text-failure-message") final Template textFailureMessageTemplate,
        @Location("partials/password-rejection") final Template passwordRejectionTemplate,
        final AppClock clock,
        final AuthenticationService authenticationService,
        final RegistrationService registrationService, final SessionStore sessionStore, final SessionCookies sessionCookies,
        final QuarkusOidcConfig quarkusOidcConfig, final OidcConfig oidcConfig, final PasswordAuthConfig passwordAuthConfig,
        final RegistrationConfig registrationConfig, final IpThrottleConfig ipThrottleConfig) {
        this.loginTemplate = loginTemplate;
        this.registerTemplate = registerTemplate;
        this.setupTemplate = setupTemplate;
        this.oidcMessagesTemplate = oidcMessagesTemplate;
        this.textFailureMessageTemplate = textFailureMessageTemplate;
        this.passwordRejectionTemplate = passwordRejectionTemplate;
        this.clock = clock;
        this.authenticationService = authenticationService;
        this.registrationService = registrationService;
        this.sessionStore = sessionStore;
        this.sessionCookies = sessionCookies;
        this.quarkusOidcConfig = quarkusOidcConfig;
        this.oidcConfig = oidcConfig;
        this.passwordAuthConfig = passwordAuthConfig;
        this.registrationConfig = registrationConfig;
        this.ipThrottleConfig = ipThrottleConfig;
    }

    // ── Login ──────────────────────────────────────────────────────────────

    /**
     * Renders the login page, optionally auto-redirecting to OIDC and surfacing error/registered states.
     */
    @GET
    @Path("login")
    @Produces(MediaType.TEXT_HTML)
    public Response loginPage(
        @QueryParam("error")      final String error,
        @QueryParam("registered") @DefaultValue("false") final boolean registered,
        @CookieParam(LOCKOUT_COOKIE) final String lockoutCookie,
        @CookieParam(OidcUserProvisioner.ERROR_COOKIE) final String oidcErrorCookie,
        @HeaderParam("Accept-Language") final String acceptLanguage) {
        // First run: no users exist yet. Send the deployer to the initial user landing page to create the
        // initial local account, and short-circuit any OIDC auto-redirect below — the first account
        // must ALWAYS be local, even in a pure-OIDC deployment (PASSWORD_AUTH_ENABLED=false): that
        // initial administrator is the sysops break-glass credential should the IdP be unavailable.
        // During initial config the local registration is always usable (both ENABLE_REGISTRATION and
        // PASSWORD_AUTH_ENABLED are ignored until a user exists).
        if (setupRequired()) {
            return Response.seeOther(URI.create("/welcome")).build();
        }
        // Auto-redirect to OIDC flow when configured, but not when there is an error or
        // success message to show (e.g. after registration or a failed OIDC attempt).
        if (quarkusOidcConfig.tenantEnabled() && oidcConfig.autoRedirect() && error == null && !registered) {
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
        final Language language = Language.fromAcceptLanguageHeader(acceptLanguage);
        // A refused OIDC login carries its 'reason' code in the short-lived cookie set by OidcUserProvisioner (the code-flow failure redirect
        // cannot carry it); an unknown/absent code falls back to the generic banner text. Rendered via oidcMessagesTemplate (a real, locale-aware
        // template render) rather than OidcDenialReason#message(String), which is Java-composed and so always English - see that partial's Javadoc.
        final String oidcErrorMessage = oidcMessagesTemplate
            .data("code", oidcErrorCookie, "provider", oidcConfig.providerName(), "fallbackToUnauthorized", true)
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, language.locale())
            .render();
        final Response.ResponseBuilder builder = Response.ok(loginTemplate
            .data("error", showError, "registered", registered, "theme", Theme.DEFAULT.value())
            .data("font", Font.DEFAULT.value())
            .data("language", language.value())
            .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, language.locale())
            .data("locked", showLocked)
            .data("lockoutSeconds", LockoutMessages.retrySeconds(lockoutRemaining))
            .data("oidcError", showOidcError)
            .data("oidcErrorMessage", oidcErrorMessage)
            .data("passwordAuthEnabled", passwordAuthConfig.enabled())
            .data("registrationEnabled", passwordAuthConfig.enabled() && registrationConfig.enabled())
            .data("oidcEnabled", quarkusOidcConfig.tenantEnabled())
            .data("oidcProviderName", oidcConfig.providerName()))
            .type(MediaType.TEXT_HTML_TYPE);
        if (showOidcError) {
            // Clear the stale OIDC session cookie so the next "Log in with Authelia" click
            // starts a fresh code flow instead of retrying the same failed session.
            builder.cookie(SessionCookies.clearedOidc());
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
                LOGGER.debug("Malformed lockout cookie value: {}", lockoutCookie, e);
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
        @FormParam("password") @Nullable final String password,
        @Context @Nullable final RoutingContext routingContext) {
        final String clientIp = ClientAddress.of(routingContext);
        final Instant now = clock.now();
        final LoginResult result = authenticationService.authenticate(
            email == null ? "" : email, password == null ? "" : password, clientIp, now);

        return switch (result) {
            case final LoginResult.Success success -> {
                final String token = sessionStore.create(
                    success.user(), Session.AUTH_SOURCE_PASSWORD, SessionCookies.userAgent(routingContext), clientIp, now);
                yield Response.seeOther(URI.create("/")).cookie(sessionCookies.issued(token, routingContext)).build();
            }
            case final LoginResult.LockedOut locked -> Response.seeOther(URI.create("/login"))
                    .cookie(lockoutCookie(locked.remaining()))
                    .build();
            case final LoginResult.InvalidCredentials _ -> Response.seeOther(URI.create("/login?error=true")).build();
        };
    }

    private static NewCookie lockoutCookie(final Duration remaining) {
        final long seconds = Math.max(1L, remaining.toSeconds());
        return new NewCookie.Builder(LOCKOUT_COOKIE)
                .value(Long.toString(seconds))
                .path("/")
                .httpOnly(true)
                .maxAge(LOCKOUT_COOKIE_MAX_AGE_SECONDS)
                .build();
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
    public Response welcomePage(@HeaderParam("Accept-Language") final String acceptLanguage) {
        if (!setupRequired()) {
            return Response.seeOther(URI.create("/login")).build();
        }
        final Language language = Language.fromAcceptLanguageHeader(acceptLanguage);
        return Response.ok(setupTemplate
                .data("theme", Theme.DEFAULT.value())
                .data("font", Font.DEFAULT.value())
                .data("language", language.value())
                .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, language.locale()))
            .build();
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
    public Response registerPage(@HeaderParam("Accept-Language") final String acceptLanguage) {
        // During the initial run the page must render even with password auth disabled — the initial (break-glass) account is always created locally.
        if (!passwordAuthConfig.enabled() && !setupRequired()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (registrationNotAllowed()) {
            return Response.ok(renderRegisterDisabled(acceptLanguage)).build();
        }
        return Response.ok(renderRegister("", "", List.of(), List.of(), false, 0L, acceptLanguage)).build();
    }

    /**
     * Handles the registration form submission: creates the user, mints a server-side session and sets the {@code diurnal_session} cookie so the new
     * account is logged straight in and redirected to the dashboard.
     */
    @POST
    @Path("register")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response register(
        @FormParam("email")           final String email,
        @FormParam("displayName")     final String displayName,
        @FormParam("password")        final String password,
        @FormParam("confirmPassword") final String confirmPassword,
        @Context @Nullable final RoutingContext routingContext,
        @HeaderParam("Accept-Language") final String acceptLanguage) {

        // Mirrors registerPage: setup always permits creating the initial (break-glass) account locally.
        if (!passwordAuthConfig.enabled() && !setupRequired()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        if (registrationNotAllowed()) {
            return Response.status(Response.Status.FORBIDDEN).entity(renderRegisterDisabled(acceptLanguage)).build();
        }

        // Null-safe copies so the form can be re-rendered with the user's input preserved on failure.
        // The password fields are deliberately NOT preserved (never re-echoed into the HTML).
        final String emailValue = email == null ? "" : email;
        final String displayNameValue = displayName == null ? "" : displayName;

        // The web form collects a confirmPassword (normalised to blank so an empty field is reported as missing rather than skipped)
        // Everything else — throttle, validation, duplicate check, account creation — is the shared RegistrationService the API also calls, so the
        // rules cannot diverge.
        final Instant now = clock.now();
        final RegistrationResult result = registrationService.register(
            email, displayName, password, confirmPassword == null ? "" : confirmPassword,
            ClientAddress.of(routingContext), now);

        return switch (result) {
            case final RegistrationResult.Success success -> {
                final String token = sessionStore.create(
                    success.user(), Session.AUTH_SOURCE_PASSWORD, SessionCookies.userAgent(routingContext), ClientAddress.of(routingContext), now);
                yield Response.seeOther(URI.create("/")).cookie(sessionCookies.issued(token, routingContext)).build();
            }
            case final RegistrationResult.LockedOut locked ->
                // The form posts via fetch (data-ajax-errors), so app.js reads the exact seconds from this
                // header and runs a live mm:ss countdown; the rendered banner (exact-seconds message) is
                // the no-JS fallback shown by a native form submit.
                Response.status(Response.Status.TOO_MANY_REQUESTS)
                        .header(LOCKOUT_RETRY_AFTER_HEADER, LockoutMessages.retrySeconds(locked.remaining()))
                        .entity(renderRegister(emailValue, displayNameValue, List.of(), List.of(),
                        false, LockoutMessages.retrySeconds(locked.remaining()), acceptLanguage))
                        .build();
            case final RegistrationResult.Invalid invalid -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(renderRegister(emailValue, displayNameValue, invalid.missingFields(), invalid.errors(), false, 0L, acceptLanguage))
                    .build();
            case final RegistrationResult.DuplicateEmail _ -> Response.status(Response.Status.BAD_REQUEST)
                    .entity(renderRegister(emailValue, displayNameValue, List.of(), List.of(), true, 0L, acceptLanguage))
                    .build();
        };
    }

    private TemplateInstance renderRegister(final String email, final String displayName,
        final List<RegistrationResult.RequiredField> missingFields, final List<RegistrationError> errors,
        final boolean duplicateEmail, final long lockoutSeconds, final String acceptLanguage) {
        final Language language = Language.fromAcceptLanguageHeader(acceptLanguage);
        final Locale locale = language.locale();
        return registerTemplate
                .data("email", email)
                .data("displayName", displayName)
                .data("missingFields", missingFields.stream().map(RegistrationResult.RequiredField::key).toList())
                .data("errors", errors.stream().map(error -> registrationErrorBanner(error, locale)).toList())
                .data("duplicateEmail", duplicateEmail)
                .data("lockoutSeconds", lockoutSeconds)
                .data("setup", setupRequired())
                .data("registrationDisabled", false)
                .data("theme", Theme.DEFAULT.value())
                .data("font", Font.DEFAULT.value())
                .data("language", language.value())
                .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale);
    }

    private String registrationErrorBanner(final RegistrationError error, final Locale locale) {
        return switch (error) {
            case final RegistrationError.FieldError fieldError ->
                textFailureMessageTemplate.data("failure", fieldError.failure()).setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
            case final RegistrationError.PasswordMismatch _ ->
                passwordRejectionTemplate.data("kind", "mismatch").setAttribute(MessageBundles.ATTRIBUTE_LOCALE, locale).render();
        };
    }

    private TemplateInstance renderRegisterDisabled(final String acceptLanguage) {
        final Language language = Language.fromAcceptLanguageHeader(acceptLanguage);
        return registerTemplate
                .data("registrationDisabled", true)
                .data("setup", false)
                .data("theme", Theme.DEFAULT.value())
                .data("font", Font.DEFAULT.value())
                .data("language", language.value())
                .setAttribute(MessageBundles.ATTRIBUTE_LOCALE, language.locale());
    }

    private static boolean setupRequired() {
        return User.count() == 0L;
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
        @CookieParam(SessionCookies.OIDC_COOKIE) @Nullable final String oidcSession) {
        // Revoke only this device's session; any other devices stay logged in. Resolve the owning
        // user first so the logout can be logged with the same identity detail as the login entry.
        Optional<User> sessionUser = Optional.empty();
        if (sessionToken != null && !sessionToken.isBlank()) {
            sessionUser = sessionStore.resolve(sessionToken, clock.now());
            sessionStore.revoke(sessionToken);
        }
        final NewCookie clearForm = sessionCookies.cleared();
        final NewCookie clearOidc = SessionCookies.clearedOidc();
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
}
