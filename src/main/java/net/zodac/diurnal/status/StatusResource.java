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

package net.zodac.diurnal.status;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * The public operational status endpoint ({@code GET /api/v1/status}): Kubernetes-style liveness/readiness probes plus the running version and
 * uptime. Deliberately anonymous (no {@code @SecurityRequirement}) so container {@code HEALTHCHECK}s, load balancers and uptime monitors can reach
 * it without a token. The HTTP status is readiness-gated — {@code 200} when the database is reachable, {@code 503} when it is not — so a probe keying
 * on the status code alone treats the app as healthy only when it can serve real traffic. All logic lives in {@link StatusService} /
 * {@link StatusAssembler}; this resource only translates the {@link SystemStatus} into a response.
 */
@Tag(name = "Status", description = "Operational status: liveness/readiness probes, version and uptime.")
@Path("/api/v1/status")
@Produces(MediaType.APPLICATION_JSON)
public class StatusResource {

    @Inject
    StatusService statusService;

    /**
     * Reports the current operational status.
     *
     * @return {@code 200} with the {@link SystemStatus} when the database is reachable, else {@code 503} with the same payload (readiness DOWN)
     */
    @GET
    @Operation(
        summary = "Operational status",
        description = "Returns the application's liveness and readiness (Kubernetes-style) probe states, the running version and the process uptime. "
        + "Responds 200 when the database is reachable and 503 when it is not, so it doubles as a container health probe. Needs no authentication.")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "The application is ready (database reachable).",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SystemStatus.class))),
        @APIResponse(responseCode = "503", description = "The application is not ready (database unreachable); readiness is 'DOWN'.",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SystemStatus.class)))
    })
    public Response status() {
        final SystemStatus current = statusService.current(Instant.now());
        return Response.status(StatusAssembler.httpStatus(current)).entity(current).build();
    }
}
