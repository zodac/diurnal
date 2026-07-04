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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import net.zodac.diurnal.auth.RoleAssigner;
import net.zodac.diurnal.config.OidcConfig;
import net.zodac.diurnal.config.PasswordAuthConfig;
import net.zodac.diurnal.config.RegistrationConfig;
import net.zodac.diurnal.stats.StatsService;
import net.zodac.diurnal.time.AppClock;
import net.zodac.diurnal.user.User;
import net.zodac.diurnal.user.UserSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Serves the top-level web UI pages: login, register, logout, settings and the dashboard.
 */
@Path("/")
public class WebResource {

    private static final Logger LOGGER = LogManager.getLogger(WebResource.class);

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
    @Inject StatsService statsService;
    @Inject RoleAssigner roleAssigner;
    @Inject AppClock clock;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @Inject
    OidcConfig oidcConfig;

    @Inject
    PasswordAuthConfig passwordAuthConfig;

    @Inject
    RegistrationConfig registrationConfig;

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
            @QueryParam("registered") @DefaultValue("false") final boolean registered) {
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
        final boolean showError = error != null && !"false".equals(error) && !showOidcError;
        final Response.ResponseBuilder builder = Response.ok(loginTemplate
                .data("error", showError, "registered", registered, "theme", "system")
                .data("font", "nova")
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
        return builder.build();
    }

    /**
     * Entry point for the OIDC code flow; authenticated users are forwarded home.
     */
    @GET
    @Path("oidc-login")
    @RolesAllowed("user")
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
        // This is called exactly once per OIDC login, so it is the right place to update
        // lastLoginAt and emit the login log (the augmentor runs on every subsequent request).
        if (!identity.isAnonymous()) {
            User.findByEmail(identity.getPrincipal().getName()).ifPresent(user -> {
                user.lastLoginAt = Instant.now();
                user.persist();
                LOGGER.debug("OIDC login: name={} email={} role={}", user.displayName, user.email, user.role);
            });
        }

        return Response.seeOther(URI.create("/")).build();
    }

    // ── First-run setup ──────────────────────────────────────────────────────

    /**
     * First-run landing page: introduces the application and guides the deployer to register the
     * initial (local, administrator) account. Once any user exists — or when local registration is
     * unavailable — it redirects to {@code /login}, so it is only ever visible during setup.
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
     * Renders the registration page. Returns {@code 404} only when password auth is disabled entirely
     * (no local registration concept). When password auth is on but registration is disabled and setup
     * is complete, it still renders the page — showing a "registration disabled" banner instead of the
     * form, rather than a bare browser error.
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
        return Response.ok(renderRegister("", "", "", "", List.of(), List.of())).build();
    }

    /**
     * Handles the registration form submission, creating the user and redirecting to /login.
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
        final String emailValue = email == null ? "" : email;
        final String displayNameValue = displayName == null ? "" : displayName;
        final String passwordValue = password == null ? "" : password;
        final String confirmValue = confirmPassword == null ? "" : confirmPassword;

        final List<String> missingFields = missingFields(emailValue, displayNameValue, passwordValue, confirmValue);
        final List<String> errors = validateRegistration(emailValue, passwordValue, confirmValue);
        final String normalised = emailValue.toLowerCase(Locale.ROOT).strip();
        if (missingFields.isEmpty() && errors.isEmpty() && User.findByEmail(normalised).isPresent()) {
            errors.add("That email is already registered.");
        }
        if (!missingFields.isEmpty() || !errors.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(renderRegister(emailValue, displayNameValue, passwordValue, confirmValue,
                            missingFields, errors))
                    .build();
        }

        final User user = new User();
        user.email = normalised;
        user.displayName = displayNameValue.strip();
        user.passwordHash = BCrypt.hashpw(passwordValue, BCrypt.gensalt(12));
        user.role = roleAssigner.roleForNewUser();
        user.persist();

        LOGGER.info("New user registered: {} (role={})", normalised, user.role);
        return Response.seeOther(URI.create("/login?registered=true")).build();
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

        if (password.length() > User.MAX_PASSWORD_LENGTH) {
            errors.add("Password must be at most " + User.MAX_PASSWORD_LENGTH + " characters.");
        }

        if (!password.isEmpty() && !confirmPassword.isEmpty() && !password.equals(confirmPassword)) {
            errors.add("The passwords did not match.");
        }

        return errors;
    }

    private TemplateInstance renderRegister(final String email, final String displayName,
            final String password, final String confirmPassword, final List<String> missingFields,
            final List<String> errors) {
        return registerTemplate
                .data("email", email)
                .data("displayName", displayName)
                .data("password", password)
                .data("confirmPassword", confirmPassword)
                .data("missingFields", missingFields)
                .data("errors", errors)
                .data("setup", setupRequired())
                .data("registrationDisabled", false)
                .data("theme", "system")
                .data("font", "nova")
                .data("maxPasswordLength", User.MAX_PASSWORD_LENGTH);
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
     * Clears the session cookies and redirects to the IdP logout (OIDC users) or {@code /login}.
     */
    @POST
    @Path("logout")
    public Response logout(@CookieParam("q_session") final String oidcSession) {
        final NewCookie clearForm    = new NewCookie.Builder("diurnal_session").value("").path("/").maxAge(0).httpOnly(true).build();
        final NewCookie clearOidc    = new NewCookie.Builder("q_session").value("").path("/").maxAge(0).httpOnly(true).build();
        // RP-initiated logout: only redirect to the IdP if the user authenticated via OIDC
        // (has a q_session cookie). Password users go straight to /login.
        // We send id_token_hint so Authelia can identify and properly terminate the IdP session.
        // Without it, Authelia accepts the end_session request but does nothing.
        final boolean hasOidcSession = oidcSession != null && !oidcSession.isBlank();
        final URI target = (hasOidcSession ? oidcConfig.logoutUrl().filter(url -> !url.isBlank()) : Optional.<String>empty())
                .map(URI::create)
                .orElse(URI.create("/login"));
        LOGGER.debug("Logout: redirecting to {}", target);
        return Response.seeOther(target).cookie(clearForm, clearOidc).build();
    }

    // ── Settings ───────────────────────────────────────────────────────────

    /**
     * Renders the settings page for the current user.
     */
    @GET
    @Path("settings")
    @RolesAllowed("user")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance settingsPage() {
        final User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
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
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateTheme(@FormParam("theme") final String theme) {
        return updateSetting(user -> user.theme = UserSettings.sanitiseTheme(theme));
    }

    /**
     * Updates the current user's font to the sanitised {@code font}. Returns {@code 204}.
     */
    @PATCH
    @Path("settings/font")
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateFont(@FormParam("font") final String font) {
        return updateSetting(user -> user.font = UserSettings.sanitiseFont(font));
    }

    /**
     * Updates the current user's calendar view to the sanitised {@code calendarView}. Returns {@code 204}.
     */
    @PATCH
    @Path("settings/calendar-view")
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateCalendarView(@FormParam("calendarView") final String calendarView) {
        return updateSetting(user -> user.calendarView = UserSettings.sanitiseCalendarView(calendarView));
    }

    /**
     * Updates the current user's timezone to the sanitised {@code timezone} (blank or unoffered →
     * server default). Returns {@code 204}.
     */
    @PATCH
    @Path("settings/timezone")
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateTimezone(@FormParam("timezone") final String timezone) {
        return updateSetting(user -> user.timezone = UserSettings.sanitiseTimezone(timezone));
    }

    /**
     * Updates the current user's page size to the sanitised {@code pageSize}. Returns {@code 204}.
     */
    @PATCH
    @Path("settings/page-size")
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updatePageSize(@FormParam("pageSize") final int pageSize) {
        return updateSetting(user -> user.pageSize = UserSettings.sanitisePageSize(pageSize));
    }

    private Response updateSetting(final Consumer<User> mutator) {
        final User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
        mutator.accept(user);
        user.persist();
        return Response.noContent().build();
    }

    /**
     * Updates the current user's display name, returning {@code 422} if it is blank.
     */
    @POST
    @Path("settings/display-name")
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateDisplayName(@FormParam("displayName") final String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return Response.status(422).build();
        }
        final User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
        user.displayName = displayName.strip();
        user.persist();
        return Response.ok().build();
    }

    /**
     * Changes the current (local) user's password. The new password is entered and then re-entered to
     * confirm (the client drives that two-step flow), so both values arrive here: {@code newPassword}
     * and its {@code confirmPassword}. Returns {@code 422} when the new password is empty or the two do
     * not match, {@code 403} for a non-local (OIDC-only) account or when password auth is disabled, and
     * {@code 200} once the new hash is persisted.
     */
    @POST
    @Path("settings/password")
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updatePassword(
            @FormParam("newPassword")     final String newPassword,
            @FormParam("confirmPassword") final String confirmPassword) {
        final User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
        // Only local accounts have a password to change; OIDC-only users (and deployments with password
        // auth switched off entirely) have none. The UI already hides the field for them — this guards
        // the endpoint directly.
        if (!passwordAuthConfig.enabled() || user.passwordHash == null || user.passwordHash.isBlank()) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        // The only validation: a password cannot be empty, and the re-entered confirmation must match.
        if (newPassword == null || newPassword.isEmpty() || !newPassword.equals(confirmPassword)) {
            return Response.status(422).build();
        }
        user.passwordHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(12));
        user.persist();
        LOGGER.info("Password changed for user: {}", user.email);
        return Response.ok().build();
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
                .data("oidcProviderName", oidcConfig.providerName())
                .data("theme", user.theme)
                .data("font", user.font)
                .data("isAdmin", user.isAdmin())
                .data("pageSize", user.pageSize)
                .data("pageSizeOptions", UserSettings.PAGE_SIZE_OPTIONS)
                .data("themeOptions", UserSettings.THEME_OPTIONS)
                .data("fontOptions", UserSettings.FONT_OPTIONS)
                .data("calendarView", user.calendarView)
                .data("calendarViewOptions", UserSettings.CALENDAR_VIEW_OPTIONS)
                .data("timezoneChoices",
                        UserSettings.timezoneChoices(clock.zone(), clock.now(), user.timezone));
    }

    // ── Dashboard (protected) ──────────────────────────────────────────────

    /**
     * Renders the dashboard with the user's calendar and three most-recent action stats.
     */
    @GET
    @Path("/")
    @RolesAllowed("user")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dashboard() {
        final User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
        final List<?> recentStats = statsService.forMostRecent(user.id, 3);
        return dashboardTemplate
                .data("email", user.email)
                .data("displayName", user.displayName)
                .data("theme", user.theme)
                .data("font", user.font)
                .data("isAdmin", user.isAdmin())
                .data("calendarView", user.calendarView)
                .data("today", clock.today(clock.zoneFor(user.timezone)).toString())
                .data("recentStats", recentStats);
    }
}
