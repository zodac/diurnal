package net.zodac.diurnal.auth;

import net.zodac.diurnal.user.User;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.mindrot.jbcrypt.BCrypt;

@Path("/api/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    private static final Logger log = Logger.getLogger(AuthResource.class);

    @Inject
    TokenService tokenService;

    @Inject
    RoleAssigner roleAssigner;

    @POST
    @Path("/register")
    @Transactional
    public Response register(@Valid RegisterRequest request) {
        String email = request.email().toLowerCase().strip();

        if (User.findByEmail(email).isPresent()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(new ErrorResponse("Email already registered"))
                    .build();
        }

        User user = new User();
        user.email = email;
        user.displayName = request.displayName().strip();
        user.passwordHash = BCrypt.hashpw(request.password(), BCrypt.gensalt(12));
        user.role = roleAssigner.roleForNewUser();
        user.persist();

        log.infof("New user registered: %s (role=%s)", email, user.role);
        String token = tokenService.generateToken(user);
        return Response.status(Response.Status.CREATED)
                .entity(new TokenResponse(token, user.email, user.displayName))
                .build();
    }

    @POST
    @Path("/login")
    public Response login(@Valid LoginRequest request) {
        String email = request.email().toLowerCase().strip();

        return User.findByEmail(email)
                .filter(u -> u.passwordHash != null)
                .filter(u -> BCrypt.checkpw(request.password(), u.passwordHash))
                .map(u -> {
                    log.debugf("Successful login: %s", email);
                    return Response.ok(
                            new TokenResponse(tokenService.generateToken(u), u.email, u.displayName)
                    ).build();
                })
                .orElseGet(() -> {
                    log.debugf("Failed login attempt for: %s", email);
                    return Response.status(Response.Status.UNAUTHORIZED)
                            .entity(new ErrorResponse("Invalid email or password"))
                            .build();
                });
    }

    public record ErrorResponse(String message) {}
}
