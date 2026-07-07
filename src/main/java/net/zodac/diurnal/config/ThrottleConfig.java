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
 * Typed view over the {@code password.auth.throttle.*} settings governing per-account login
 * throttling: after {@link #maxAttempts()} consecutive failures for a given email, further
 * attempts for that account are locked out for {@link #lockoutDuration()}.
 *
 * <p>
 * This is a deliberately per-account control (the reverse proxy already handles per-IP limits);
 * it exists to slow password-guessing against a single known account. State is held in memory,
 * so it resets on restart and is not shared across instances — acceptable for the single-instance
 * deployment.
 */
@ConfigMapping(prefix = "password.auth.throttle")
public interface ThrottleConfig {

    /**
     * Whether per-account login throttling is enabled. When {@code false}, no attempts are tracked
     * and no account is ever locked out.
     *
     * @return {@code true} when throttling is enabled, defaulting to {@code true}
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * The number of consecutive failed login attempts (per account) tolerated before the account is
     * locked out. The attempt that reaches this count triggers the lockout.
     *
     * @return the maximum consecutive failures, defaulting to {@code 5}
     */
    @WithName("max-attempts")
    @WithDefault("5")
    int maxAttempts();

    /**
     * How long an account stays locked once {@link #maxAttempts()} is reached. Expressed as an
     * ISO-8601 duration (e.g. {@code PT15M} = 15 minutes).
     *
     * @return the lockout duration, defaulting to 15 minutes
     */
    @WithName("lockout-duration")
    @WithDefault("PT5M")
    Duration lockoutDuration();
}
