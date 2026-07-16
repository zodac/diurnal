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

package net.zodac.diurnal.user;

/**
 * The outcome of a profile/preference update by {@link ProfileService}: the caller (the Settings page's HTMX endpoints or the REST API's
 * {@code PATCH /api/v1/users/me}) maps each case to its own response medium. Mirrors the {@code LoginResult} pattern shared by the two login
 * surfaces, so the update rules cannot diverge between the surfaces (only their presentation can).
 */
public sealed interface ProfileResult permits ProfileResult.Updated, ProfileResult.Invalid {

    /**
     * The update was applied and persisted.
     */
    record Updated() implements ProfileResult {

    }

    /**
     * The submitted value was rejected; nothing was changed.
     *
     * @param message the human-readable rejection reason (the same wording on every surface)
     */
    record Invalid(String message) implements ProfileResult {

    }
}
