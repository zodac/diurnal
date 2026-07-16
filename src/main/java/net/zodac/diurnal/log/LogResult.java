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

import net.zodac.diurnal.action.Action;

/**
 * The outcome of a day-count operation by {@link LogService}: the caller (the web UI's HTMX resource or the public REST API) maps each case to its
 * own response medium — a day-action-item partial for HTMX, a JSON DTO + status code for the API. Mirrors the {@code LoginResult} pattern shared by
 * the two login surfaces, so the write rules cannot diverge between the surfaces (only their presentation can).
 */
sealed interface LogResult permits LogResult.Updated, LogResult.FutureDate, LogResult.NotOwned {

    /**
     * The operation succeeded; {@link #count()} is the day's resulting count ({@code 0} when no entry remains) and {@link #action()} the owned
     * action, so the HTMX caller can render its partial without re-fetching.
     *
     * @param action the owned action the operation applied to
     * @param count  the day's resulting count
     */
    record Updated(Action action, int count) implements LogResult {

    }

    /**
     * The date is in the future in the acting user's timezone — logging is only allowed for today or earlier.
     */
    record FutureDate() implements LogResult {

    }

    /**
     * The action does not exist or is not owned by the acting user.
     */
    record NotOwned() implements LogResult {

    }
}
