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
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import net.zodac.diurnal.user.Role;

/**
 * Renders the styled 403 page (instead of the default JSON error) when access is denied.
 */
@Provider
public class ForbiddenExceptionMapper implements ExceptionMapper<ForbiddenException> {

    @Inject
    @Location("error-403")
    Template errorTemplate;

    @Inject SecurityIdentity identity;

    @Override
    public Response toResponse(ForbiddenException exception) {
        // Read displayName and isAdmin from the identity attributes — set at auth time by
        // UserIdentities (session auth) / OidcUserProvisioner, so no DB call is needed here.
        String displayName = "";
        boolean isAdmin = false;
        if (!identity.isAnonymous()) {
            final String attr = identity.getAttribute("displayName");
            displayName = attr != null ? attr : identity.getPrincipal().getName();
            isAdmin = identity.hasRole(Role.Values.ADMIN);
        }
        return Response.status(Response.Status.FORBIDDEN)
                .entity(errorTemplate
                        .data("theme", "system")
                        .data("font", "nova")
                        .data("displayName", displayName)
                        .data("isAdmin", isAdmin))
                .type(MediaType.TEXT_HTML_TYPE)
                .build();
    }
}
