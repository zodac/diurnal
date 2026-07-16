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

package net.zodac.diurnal.action;

/**
 * The pure validation rules for a user-submitted action, applied by {@link ActionService} (the single mutation path shared by the web UI and the
 * public REST API) so both surfaces accept exactly the same values.
 */
final class ActionValidation {

    /**
     * The default action colour — a neutral slate, deliberately not the brand indigo (a brand-coloured dot would vanish into the full calendar's
     * brand-filled "today" cell).
     */
    static final String DEFAULT_COLOUR = "#64748b";

    /**
     * The longest permitted action name (the {@code actions.name} column width).
     */
    static final int NAME_MAX_LENGTH = 100;

    private ActionValidation() {

    }

    /**
     * Whether the submitted colour is an invalid {@code #rrggbb} hex value. Submissions with a malformed colour are rejected by the caller
     * ({@link ActionService}) rather than coerced; an <em>absent</em> colour on creation falls back to {@link #DEFAULT_COLOUR}.
     *
     * @param colour the submitted colour
     * @return {@code true} when the colour is an invalid hex value
     */
    static boolean isColourInvalid(final String colour) {
        return !colour.matches("^#[0-9a-fA-F]{6}$");
    }
}
