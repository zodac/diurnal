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

package net.zodac.diurnal.auth;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import net.zodac.diurnal.time.AppClock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Periodically deletes sessions past their absolute expiry, replacing the automatic TTL eviction a cache store would provide. Idle-expired sessions
 * are already pruned lazily when presented; this mops up those never presented again so the table cannot grow unbounded.
 */
@ApplicationScoped
public class SessionSweeper {

    private static final Logger LOGGER = LogManager.getLogger(SessionSweeper.class);

    @Inject
    AppClock clock;

    /**
     * Deletes all sessions whose absolute expiry has passed. The interval is configurable via {@code session.cleanup-interval} (default hourly).
     */
    @Scheduled(every = "{session.cleanup-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    @Transactional
    void sweepExpiredSessions() {
        final long removed = Session.deleteExpired(clock.now());
        if (removed > 0L) {
            LOGGER.debug("Swept {} expired session(s)", removed);
        }
    }
}
