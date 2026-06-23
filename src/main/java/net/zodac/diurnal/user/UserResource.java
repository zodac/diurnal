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
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST API endpoint exposing the authenticated user's own profile.
 */
@Tag(name = "Users", description = "Profile details for a user.")
@Path("/api/users")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject
    SecurityIdentity identity;

    /**
     * Returns the current user as a {@link UserDto} ({@code 200}), or {@code 404} if not found.
     */
    @GET
    @Path("/me")
    @Operation(
            summary = "Get the current user",
            description = "Returns the authenticated user's profile: id, email, display name, role, and a "
                    + "nested preferences object (theme, pageSize, calendarView, timezone)."
    )
    @SecurityRequirement(name = "BearerAuth")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The authenticated user's profile.",
                content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = UserDto.class))),
        @APIResponse(responseCode = "401", description = "Missing or invalid Bearer token."),
        @APIResponse(responseCode = "404", description = "The authenticated account no longer exists.")
    })
    public Response me() {
        // Resolve via the authenticated principal (the upn/email claim) rather than the JsonWebToken
        // subject: with OIDC enabled the default JsonWebToken producer is the OIDC one, which is not
        // populated for a smallrye Bearer token, so jwt.getSubject() would be null. SecurityIdentity
        // works for both the Bearer and OIDC flows.
        return User.findByEmail(identity.getPrincipal().getName())
                .map(u -> Response.ok(UserDto.from(u)).build())
                .orElse(Response.status(Response.Status.NOT_FOUND).build());
    }
}
