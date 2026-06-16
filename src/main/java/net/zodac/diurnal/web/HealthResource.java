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

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import javax.sql.DataSource;

/** Liveness/readiness endpoint that reports {@code 200} only when the database is reachable. */
@Path("/health")
public class HealthResource {

    @Inject
    DataSource dataSource;

    /** Returns {@code 200 OK} when the database connection is valid, else {@code 503}. */
    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response health() {
        try (Connection conn = dataSource.getConnection()) {
            conn.isValid(1);
            return Response.ok("OK").build();
        } catch (Exception e) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).entity("DB unavailable").build();
        }
    }
}
