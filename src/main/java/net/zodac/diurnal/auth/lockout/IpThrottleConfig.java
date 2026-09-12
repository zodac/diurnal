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

package net.zodac.diurnal.auth.lockout;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import java.time.Duration;

/**
 * Typed view over the {@code auth.ip-throttle.*} settings governing the single, global per-<em>IP</em> lockout: after {@link #maxAttempts()} failed
 * logins <em>or</em> registrations from one client IP within {@link #lockoutDuration()}, that IP is locked out of both logging in and registering.
 *
 * <p>
 * This is the only auth lockout — there is deliberately no per-account (email) dimension, since a per-account lockout would let an attacker deny
 * service to a targeted victim by failing logins for their email. Keying purely on the client IP avoids that footgun.
 *
 * <p>
 * The client IP is resolved by {@code net.zodac.diurnal.http.ClientAddress} from Vert.x {@code remoteAddress()}, which honours
 * {@code quarkus.http.proxy.proxy-address-forwarding} ({@code TRUST_X_FORWARDED_HEADERS}), or from {@code CF-Connecting-IP} when
 * {@code TRUST_CLOUDFLARE_HEADER} is on; this control is therefore only meaningful when whichever of those is trusted is configured correctly.
 * Because many users can share one IP (NAT/CGNAT), the limit is deliberately generous and a counter decays after a quiet window so shared IPs don't
 * accumulate unrelated failures.
 */
@ConfigMapping(prefix = "auth.ip-throttle")
public interface IpThrottleConfig {

    /**
     * Whether the per-IP lockout is enabled. When {@code false}, no attempts are tracked and no IP is ever locked out.
     *
     * @return {@code true} when enabled, defaulting to {@code true}
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Failed logins or registrations from one client IP tolerated before that IP is locked out.
     *
     * @return the maximum failures, defaulting to {@code 15}
     */
    @WithName("max-attempts")
    @WithDefault("15")
    int maxAttempts();

    /**
     * How long an IP stays locked once {@link #maxAttempts()} is reached (also the decay window). ISO-8601 duration, e.g. {@code PT15M}.
     *
     * @return the lockout duration, defaulting to 15 minutes
     */
    @WithName("lockout-duration")
    @WithDefault("PT15M")
    Duration lockoutDuration();

    /**
     * How often {@link IpThrottle} drops the tracked IPs whose counters have decayed, bounding the in-memory map to the addresses actually being
     * counted rather than every address ever seen. Also referenced directly by the eviction's
     * {@code @Scheduled(every = "{auth.ip-throttle.cleanup-interval}")}; declared here so the property maps cleanly under the
     * {@code auth.ip-throttle.*} prefix. ISO-8601 duration.
     *
     * @return the cleanup interval, defaulting to 1 hour
     */
    @WithName("cleanup-interval")
    @WithDefault("PT1H")
    @SuppressWarnings("unused") // IpThrottle reads the key via @Scheduled(every = "{auth.ip-throttle.cleanup-interval}"); no Java caller
    Duration cleanupInterval();
}
