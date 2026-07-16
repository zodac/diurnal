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

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The operational status payload served by {@code GET /api/v1/status}: two Kubernetes-style probe states (liveness and readiness), the running
 * release version, and the process uptime. A pure data carrier — the derivation lives in {@link StatusAssembler}.
 *
 * @param liveness  whether the application itself is up and serving (always {@link HealthState#UP} when this payload is returned)
 * @param readiness whether the application can reach its database dependency
 * @param version   the running release version
 * @param uptime    the elapsed time since startup, formatted {@code HH:mm:ss.SSS} with leading zero-value groups omitted
 */
@Schema(description = "Operational status: liveness/readiness probes, running version and process uptime.")
public record SystemStatus(
    @Schema(examples = "UP", description = "Liveness probe: whether the application is up and serving requests. Always 'UP' when this payload is "
    + "returned.") HealthState liveness,
    @Schema(examples = "UP", description = "Readiness probe: whether the application can reach its database dependency ('UP' or 'DOWN').")
    HealthState readiness,
    @Schema(examples = "1.2.3", description = "The running release version.") String version,
    @Schema(examples = "3:12:45.678", description = "Elapsed time since startup, formatted HH:mm:ss.SSS with leading zero-value groups omitted "
    + "(e.g. '45.678' under a minute, '12:45.678' under an hour).") String uptime) {

}
