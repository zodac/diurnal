package dev.lifetracker.web;

import dev.lifetracker.user.User;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

import java.net.URI;

@Path("/")
public class WebResource {

    private static final Logger log = Logger.getLogger(WebResource.class);

    @Inject @Location("login")    Template loginTemplate;
    @Inject @Location("register") Template registerTemplate;
    @Inject @Location("dashboard") Template dashboardTemplate;

    @Inject SecurityIdentity identity;

    @ConfigProperty(name = "password.auth.enabled", defaultValue = "true")
    boolean passwordAuthEnabled;

    // ── Login ──────────────────────────────────────────────────────────────

    @GET
    @Path("login")
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance loginPage(
            @QueryParam("error")      @DefaultValue("false") boolean error,
            @QueryParam("registered") @DefaultValue("false") boolean registered) {
        return loginTemplate.data("error", error, "registered", registered,
                "passwordAuthEnabled", passwordAuthEnabled);
    }

    // ── Register ───────────────────────────────────────────────────────────

    @GET
    @Path("register")
    @Produces(MediaType.TEXT_HTML)
    public Response registerPage(@QueryParam("error") @DefaultValue("") String error) {
        if (!passwordAuthEnabled) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(registerTemplate.data("error", error)).build();
    }

    @POST
    @Path("register")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response register(
            @FormParam("email")       String email,
            @FormParam("displayName") String displayName,
            @FormParam("password")    String password) {

        if (!passwordAuthEnabled) {
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
        user.persist();

        log.infof("New user registered: %s", normalised);
        return Response.seeOther(URI.create("/login?registered=true")).build();
    }

    // ── Logout ────────────────────────────────────────────────────────────

    @POST
    @Path("logout")
    public Response logout() {
        // The session is stateless (stored entirely in the encrypted cookie).
        // Clearing the cookie is sufficient to end the session.
        NewCookie clear = new NewCookie.Builder("lt_session")
                .value("")
                .path("/")
                .maxAge(0)
                .httpOnly(true)
                .build();
        return Response.seeOther(URI.create("/login")).cookie(clear).build();
    }

    // ── Dashboard (protected) ──────────────────────────────────────────────

    @GET
    @Path("/")
    @RolesAllowed("user")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    public TemplateInstance dashboard() {
        String email = identity.getPrincipal().getName();
        String displayName = User.findByEmail(email)
                .map(u -> u.displayName)
                .orElse(email);
        return dashboardTemplate.data("email", email, "displayName", displayName);
    }
}
