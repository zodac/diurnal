package dev.lifetracker.web;

import dev.lifetracker.auth.RoleAssigner;
import dev.lifetracker.stats.StatsService;
import dev.lifetracker.time.AppClock;
import dev.lifetracker.user.User;
import dev.lifetracker.user.UserSettings;
import io.quarkus.oidc.IdToken;
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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

@Path("/")
public class WebResource {

    private static final Logger log = Logger.getLogger(WebResource.class);

    @Inject @Location("login")    Template loginTemplate;
    @Inject @Location("register") Template registerTemplate;
    @Inject @Location("dashboard") Template dashboardTemplate;
    @Inject @Location("settings") Template settingsTemplate;

    @Inject SecurityIdentity identity;
    @Inject StatsService statsService;
    @Inject RoleAssigner roleAssigner;
    @Inject AppClock clock;

    @ConfigProperty(name = "password.auth.enabled", defaultValue = "true")
    boolean passwordAuthEnabled;

    @ConfigProperty(name = "registration.enabled", defaultValue = "true")
    boolean registrationEnabled;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @ConfigProperty(name = "oidc.provider.name", defaultValue = "your identity provider")
    String oidcProviderName;

    @ConfigProperty(name = "oidc.auto.redirect", defaultValue = "false")
    boolean oidcAutoRedirect;

    @Inject @IdToken JsonWebToken idToken;

    @ConfigProperty(name = "oidc.logout.url")
    Optional<String> oidcLogoutUrl;

    // ── Login ──────────────────────────────────────────────────────────────

    @GET
    @Path("login")
    @Produces(MediaType.TEXT_HTML)
    public Response loginPage(
            @QueryParam("error")      String error,
            @QueryParam("registered") @DefaultValue("false") boolean registered) {
        // Auto-redirect to OIDC flow when configured, but not when there is an error or
        // success message to show (e.g. after registration or a failed OIDC attempt).
        if (oidcEnabled && oidcAutoRedirect && error == null && !registered) {
            return Response.seeOther(URI.create("/oidc-login")).build();
        }
        // error is null when absent, "" when present with no value (?error), or a string value.
        // Quarkus form auth redirects to /login?error (no value) on failure — treat key presence as truthy.
        // ?error=oidc is set by quarkus.oidc.authentication.error-path and shown separately.
        //
        // When the IdP itself denies access (e.g. Authelia access-control rule), it appends its own
        // query params to the redirect_uri. Quarkus then forwards everything to the error-path,
        // producing a double-? URL like /login?error=oidc?error=access_denied&... — redirect to clean.
        if (error != null && error.startsWith("oidc") && !error.equals("oidc")) {
            return Response.seeOther(URI.create("/login?error=oidc")).build();
        }
        boolean showOidcError = "oidc".equals(error);
        boolean showError = error != null && !"false".equals(error) && !showOidcError;
        Response.ResponseBuilder builder = Response.ok(loginTemplate
                .data("error", showError, "registered", registered, "theme", "system")
                .data("oidcError", showOidcError)
                .data("passwordAuthEnabled", passwordAuthEnabled)
                .data("registrationEnabled", passwordAuthEnabled && registrationEnabled)
                .data("oidcEnabled", oidcEnabled)
                .data("oidcProviderName", oidcProviderName))
                .type(MediaType.TEXT_HTML_TYPE);
        if (showOidcError) {
            // Clear the stale OIDC session cookie so the next "Log in with Authelia" click
            // starts a fresh code flow instead of retrying the same failed session.
            builder.cookie(new NewCookie.Builder("q_session").value("").path("/").maxAge(0).httpOnly(true).build());
        }
        return builder.build();
    }

    @GET
    @Path("oidc-login")
    @RolesAllowed("user")
    public Response oidcLogin() {
        // Unauthenticated requests never reach here — the oidc-trigger permission policy
        // intercepts them first and issues the OIDC Authorization Code challenge.
        // Authenticated users (e.g. navigating from browser history) are forwarded home.
        return Response.seeOther(URI.create("/")).build();
    }

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
                log.debugf("OIDC login: name=%s email=%s role=%s", user.displayName, user.email, user.role);
            });
        }

        return Response.seeOther(URI.create("/")).build();
    }

    // ── Register ───────────────────────────────────────────────────────────

    @GET
    @Path("register")
    @Produces(MediaType.TEXT_HTML)
    public Response registerPage(@QueryParam("error") @DefaultValue("") String error) {
        if (!passwordAuthEnabled || !registrationEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(registerTemplate.data("error", error, "theme", "system")).build();
    }

    @POST
    @Path("register")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response register(
            @FormParam("email")       String email,
            @FormParam("displayName") String displayName,
            @FormParam("password")    String password) {

        if (!passwordAuthEnabled || !registrationEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        if (email == null || displayName == null || password == null
                || email.isBlank() || displayName.isBlank() || password.length() < 8) {
            return Response.seeOther(URI.create("/register?error=invalid")).build();
        }

        String normalised = email.toLowerCase().strip();
        if (User.findByEmail(normalised).isPresent()) {
            return Response.seeOther(URI.create("/register?error=email_taken")).build();
        }

        User user = new User();
        user.email = normalised;
        user.displayName = displayName.strip();
        user.passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(12));
        user.role = roleAssigner.roleForNewUser();
        user.persist();

        log.infof("New user registered: %s (role=%s)", normalised, user.role);
        return Response.seeOther(URI.create("/login?registered=true")).build();
    }

    // ── Logout ────────────────────────────────────────────────────────────

    @POST
    @Path("logout")
    public Response logout(@CookieParam("q_session") String qSession) {
        NewCookie clearForm    = new NewCookie.Builder("lt_session").value("").path("/").maxAge(0).httpOnly(true).build();
        NewCookie clearOidc    = new NewCookie.Builder("q_session").value("").path("/").maxAge(0).httpOnly(true).build();
        // RP-initiated logout: only redirect to the IdP if the user authenticated via OIDC
        // (has a q_session cookie). Password users go straight to /login.
        // We send id_token_hint so Authelia can identify and properly terminate the IdP session.
        // Without it, Authelia accepts the end_session request but does nothing.
        boolean hasOidcSession = qSession != null && !qSession.isBlank();
        URI target = (hasOidcSession ? oidcLogoutUrl.filter(url -> !url.isBlank()) : Optional.<String>empty())
                .map(URI::create)
                .orElse(URI.create("/login"));
        log.debugf("Logout: redirecting to %s", target);
        return Response.seeOther(target).cookie(clearForm, clearOidc).build();
    }

    // ── Settings ───────────────────────────────────────────────────────────

    @GET
    @Path("settings")
    @RolesAllowed("user")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance settingsPage() {
        User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
        return settingsView(user, false);
    }

    @POST
    @Path("settings")
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance updateSettings(
            @FormParam("theme") @DefaultValue("system") String theme,
            @FormParam("pageSize") @DefaultValue("10") int pageSize,
            @FormParam("calendarView") @DefaultValue("full") String calendarView,
            @FormParam("timezone") @DefaultValue("") String timezone) {
        User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
        user.theme = UserSettings.sanitiseTheme(theme);
        user.pageSize = UserSettings.sanitisePageSize(pageSize);
        user.calendarView = UserSettings.sanitiseCalendarView(calendarView);
        user.timezone = UserSettings.sanitiseTimezone(timezone);
        user.persist();
        return settingsView(user, true);
    }

    @POST
    @Path("settings/display-name")
    @RolesAllowed("user")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    public Response updateDisplayName(@FormParam("displayName") String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return Response.status(422).build();
        }
        User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
        user.displayName = displayName.strip();
        user.persist();
        return Response.ok().build();
    }

    private TemplateInstance settingsView(User user, boolean saved) {
        return settingsTemplate
                .data("email", user.email)
                .data("displayName", user.displayName)
                .data("theme", user.theme)
                .data("isAdmin", user.isAdmin())
                .data("pageSize", user.pageSize)
                .data("pageSizeOptions", UserSettings.PAGE_SIZE_OPTIONS)
                .data("themeOptions", UserSettings.THEME_OPTIONS)
                .data("calendarView", user.calendarView)
                .data("calendarViewOptions", UserSettings.CALENDAR_VIEW_OPTIONS)
                .data("timezoneChoices",
                        UserSettings.timezoneChoices(clock.zone(), clock.now(), user.timezone))
                .data("saved", saved);
    }

    // ── Dashboard (protected) ──────────────────────────────────────────────

    @GET
    @Path("/")
    @RolesAllowed("user")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dashboard() {
        User user = User.findByEmail(identity.getPrincipal().getName()).orElseThrow();
        List<?> recentStats = statsService.forMostRecent(user.id, 3);
        return dashboardTemplate
                .data("email", user.email)
                .data("displayName", user.displayName)
                .data("theme", user.theme)
                .data("isAdmin", user.isAdmin())
                .data("calendarView", user.calendarView)
                .data("today", clock.today(clock.zoneFor(user.timezone)).toString())
                .data("recentStats", recentStats);
    }
}
