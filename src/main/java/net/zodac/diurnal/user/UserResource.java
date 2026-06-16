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

package net.zodac.diurnal.user;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;

/** REST API endpoint exposing the authenticated user's own profile. */
@Path("/api/users")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    JsonWebToken jwt;

    /** Returns the current user as a {@link UserDto} ({@code 200}), or {@code 404} if not found. */
    @GET
    @Path("/me")
    public Response me() {
        final UUID userId = UUID.fromString(jwt.getSubject());
        return User.find("id", userId).<User>firstResultOptional()
                .map(u -> Response.ok(UserDto.from(u)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }
}
