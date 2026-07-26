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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Renders the styled 403 page (instead of the default JSON error) when access is denied.
 */
@ApplicationScoped
public class ForbiddenExceptionMapper {

    private final Template errorTemplate;
    private final SecurityIdentity identity;

    /**
     * Injects the 403 page template and the current request's security identity.
     *
     * @param errorTemplate the styled 403 page template
     * @param identity the denied request's security identity
     */
    @Inject
    public ForbiddenExceptionMapper(@Location("error-403") final Template errorTemplate, final SecurityIdentity identity) {
        this.errorTemplate = errorTemplate;
        this.identity = identity;
    }

    /**
     * Maps a {@link ForbiddenException} to the styled 403 HTML page (shared with {@link NotFoundExceptionMapper} via {@link ErrorPages}). The
     * {@link SecurityIdentity} is resolved synchronously here (unlike the 404 mapper, which runs on unmatched routes) because a denied request has
     * already passed through authentication.
     *
     * @param exception the forbidden exception (its cause does not affect the page)
     * @return the styled 403 HTML {@link Response}
     */
    @ServerExceptionMapper
    public Response toResponse(final ForbiddenException exception) {
        return ErrorPages.render(errorTemplate, Response.Status.FORBIDDEN, identity);
    }
}
