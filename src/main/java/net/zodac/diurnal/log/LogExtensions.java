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

package net.zodac.diurnal.log;

import io.quarkus.qute.TemplateExtension;

/**
 * Template-facing constants for the day-log UI, exposed under the {@code log:} namespace (the same zero-argument namespaced pattern as
 * {@code role:options} in {@code UserRowExtensions}).
 *
 * <p>
 * Lets the day-action-item partial gate the increment button on the cap without hard-coding the number, so the template can never drift from the
 * authoritative {@link ActionLog#MAX_DAILY_COUNT}.
 */
public final class LogExtensions {

    private LogExtensions() {

    }

    /**
     * The maximum count a single day's action tally can reach, mirroring the {@code SMALLINT} column cap. Exposed as {@code {log:maxDailyCount}} so
     * the day-action-item partial hides the increment control at the cap using the same value the backend enforces.
     *
     * @return {@link ActionLog#MAX_DAILY_COUNT}
     */
    @TemplateExtension(namespace = "log")
    public static int maxDailyCount() {
        return ActionLog.MAX_DAILY_COUNT;
    }
}
