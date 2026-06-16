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
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Renders the styled 404 page (instead of the default JSON error) for unknown routes. */
@ApplicationScoped
public class NotFoundExceptionMapper {

    @Inject
    @Location("error-404")
    Template errorTemplate;
    @Inject CurrentIdentityAssociation identityAssociation;

    /** Maps a {@link NotFoundException} to the styled 404 HTML page. */
    @ServerExceptionMapper
    public Uni<Response> toResponse(NotFoundException exception) {
        return identityAssociation.getDeferredIdentity().map(identity -> {
            String displayName = "";
            boolean isAdmin = false;
            if (!identity.isAnonymous()) {
                final String attr = identity.getAttribute("displayName");
                displayName = attr != null ? attr : identity.getPrincipal().getName();
                isAdmin = identity.hasRole("admin");
            }
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(errorTemplate
                            .data("theme", "system")
                            .data("displayName", displayName)
                            .data("isAdmin", isAdmin))
                    .type(MediaType.TEXT_HTML_TYPE)
                    .build();
        });
    }
}
