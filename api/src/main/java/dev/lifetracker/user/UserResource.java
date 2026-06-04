package dev.lifetracker.user;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.UUID;

@Path("/api/users")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    JsonWebToken jwt;

    @GET
    @Path("/me")
    public Response me() {
        UUID userId = UUID.fromString(jwt.getSubject());
        return User.find("id", userId).<User>firstResultOptional()
                .map(u -> Response.ok(UserDto.from(u)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }
}
