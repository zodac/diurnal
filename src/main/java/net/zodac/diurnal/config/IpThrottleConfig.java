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
 * Typed view over the {@code password.auth.ip-throttle.*} settings governing per-<em>IP</em> login
 * throttling: after {@link #maxAttempts()} failures from a single client IP (across any accounts) within
 * {@link #lockoutDuration()}, that IP is locked out. This complements the per-account throttle
 * ({@link ThrottleConfig}) — the account throttle protects a targeted account across every source, while
 * this slows a single host rotating through many accounts.
 *
 * <p>
 * The client IP comes from Vert.x {@code remoteAddress()}, which honours
 * {@code quarkus.http.proxy.proxy-address-forwarding} ({@code TRUST_X_FORWARDED_HEADERS}); this control
 * is therefore only meaningful when that is configured correctly behind a trusted proxy. Because many
 * users can share one IP (NAT/CGNAT), the default limit is deliberately more generous than the
 * per-account one, and a counter decays after a quiet window.
 */
@ConfigMapping(prefix = "password.auth.ip-throttle")
public interface IpThrottleConfig {

    /**
     * Whether per-IP login throttling is enabled.
     *
     * @return {@code true} when enabled, defaulting to {@code true}
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Failures from one client IP (across any accounts) tolerated before that IP is locked out.
     *
     * @return the maximum failures, defaulting to {@code 15}
     */
    @WithName("max-attempts")
    @WithDefault("15")
    int maxAttempts();

    /**
     * How long an IP stays locked once {@link #maxAttempts()} is reached (also the decay window).
     * ISO-8601 duration, e.g. {@code PT15M}.
     *
     * @return the lockout duration, defaulting to 15 minutes
     */
    @WithName("lockout-duration")
    @WithDefault("PT15M")
    Duration lockoutDuration();
}
