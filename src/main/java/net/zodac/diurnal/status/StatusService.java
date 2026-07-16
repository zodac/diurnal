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

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.time.Instant;
import javax.sql.DataSource;
import net.zodac.diurnal.web.AppInfo;

/**
 * Assembles the live operational status of the application for {@code GET /api/v1/status}: the readiness probe (a real database round-trip), the
 * running release version and the process uptime. The startup instant is captured once on {@link StartupEvent}; the database check and CDI wiring are
 * the untestable glue, with the pure derivation delegated to {@link StatusAssembler}.
 */
@ApplicationScoped
public class StatusService {

    private Instant startedAt = Instant.now();

    @Inject
    DataSource dataSource;

    @Inject
    AppInfo appInfo;

    /**
     * Records the startup instant so uptime is measured from when the application actually came up, not from lazy bean construction.
     *
     * @param ev the application startup event
     */
    @SuppressWarnings("unused") // CDI startup observer — invoked by Quarkus, not called directly
    void onStart(@Observes final StartupEvent ev) {
        startedAt = Instant.now();
    }

    /**
     * Builds the current {@link SystemStatus}: liveness is always {@code UP} (this code is running), readiness reflects whether the database is
     * reachable, and the uptime is measured from the recorded startup instant to the supplied {@code now}.
     *
     * @param now the instant to measure uptime against (from {@code Instant.now()} at the call site)
     * @return the assembled status payload
     */
    public SystemStatus current(final Instant now) {
        return StatusAssembler.assemble(isDatabaseReachable(), appInfo.getVersion(), startedAt, now);
    }

    private boolean isDatabaseReachable() {
        try (Connection conn = dataSource.getConnection()) {
            return conn.isValid(1);
        } catch (final Exception e) {
            return false;
        }
    }
}
