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

package net.zodac.diurnal.stub;

import java.time.Duration;
import net.zodac.diurnal.config.UpdateCheckConfig;

/**
 * Reusable {@link UpdateCheckConfig} stub: the update check is reported disabled with a short timeout. Reused wherever an {@link UpdateCheckConfig}
 * instance is required but its values are irrelevant to the test.
 */
public record StubUpdateCheckConfig() implements UpdateCheckConfig {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Duration timeout() {
        return Duration.ofSeconds(1L);
    }
}
