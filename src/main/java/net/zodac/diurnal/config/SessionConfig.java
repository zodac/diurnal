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

package net.zodac.diurnal.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.time.Duration;

/**
 * Typed view over the {@code session.*} settings governing the server-side session store shared by the web UI and the REST API.
 *
 * <p>
 * A session stays usable until either bound is crossed: {@link #idleTimeout()} since it was last used, or {@link #absoluteTimeout()} since it was
 * created. Both are ISO-8601 durations (e.g. {@code P30D}). Sessions are revocable, so these can be generous without the exposure a non-revocable
 * token carries.
 */
@ConfigMapping(prefix = "session")
public interface SessionConfig {

    /**
     * How long a session may sit idle (no authenticated request) before it stops being valid. Measured from the session's {@code lastUsedAt}, which
     * is bumped on every request. ISO-8601 duration.
     *
     * @return the sliding idle timeout, defaulting to 30 days
     */
    @WithName("idle-timeout")
    @WithDefault("P30D")
    Duration idleTimeout();

    /**
     * The hard cap on a session's lifetime regardless of activity, measured from creation. Once passed, the client must log in afresh even if
     * continuously active. ISO-8601 duration.
     *
     * @return the absolute lifetime, defaulting to 90 days
     */
    @WithName("absolute-timeout")
    @WithDefault("P90D")
    Duration absoluteTimeout();

    /**
     * How often the {@code SessionSweeper} deletes absolute-expired sessions. Also referenced directly by the sweeper's
     * {@code @Scheduled(every = "{session.cleanup-interval}")}; declared here so the property maps cleanly under the {@code session.*} prefix.
     * ISO-8601 duration.
     *
     * @return the cleanup interval, defaulting to 1 hour
     */
    @WithName("cleanup-interval")
    @WithDefault("PT1H")
    Duration cleanupInterval();

    /**
     * The name of the cookie carrying the session token for the web UI.
     *
     * @return the cookie name, defaulting to {@code diurnal_session}
     */
    @WithName("cookie-name")
    @WithDefault("diurnal_session")
    String cookieName();
}
