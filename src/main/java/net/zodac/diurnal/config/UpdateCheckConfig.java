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
import java.time.Duration;

/**
 * Typed view over the {@code app.update-check.*} settings driving the admin-only "newer version available" footer indicator (see
 * {@code net.zodac.diurnal.update.UpdateCheckService}). When enabled, the running version is compared against the latest published GitHub release
 * exactly once, at application startup; the result is logged and reused for the footer (it is never refreshed, so it may go stale over a long
 * uptime).
 */
@ConfigMapping(prefix = "app.update-check")
public interface UpdateCheckConfig {

    /**
     * Whether the startup update check is enabled. When {@code false} no outbound lookup is ever made and the footer shows no indicator.
     *
     * @return {@code true} when the update check is enabled
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The connect/read timeout for the single startup latest-release lookup. Kept short so a slow or unreachable GitHub only briefly delays startup -
     * a timed-out lookup simply yields no indicator.
     *
     * @return the lookup timeout
     */
    @WithDefault("PT3S")
    Duration timeout();
}
