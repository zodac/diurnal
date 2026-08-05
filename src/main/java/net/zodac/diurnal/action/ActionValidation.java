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

import net.zodac.diurnal.colour.Colours;

/**
 * The pure validation rules for a user-submitted action, applied by {@link ActionService} (the single mutation path shared by the web UI and the
 * public REST API) so both surfaces accept exactly the same values.
 *
 * <p>
 * Only the colour lives here: the action's NAME is validated by the shared {@code net.zodac.diurnal.text} pipeline against
 * {@code TextFields.ACTION_NAME}, alongside every other free-text input in the app.
 */
final class ActionValidation {

    /**
     * The default action colour — a neutral slate, deliberately not the brand indigo (a brand-coloured dot would vanish into the full calendar's
     * brand-filled "today" cell).
     */
    static final String DEFAULT_COLOUR = "#64748b";

    private ActionValidation() {

    }

    /**
     * Whether the submitted colour is an invalid {@code #rrggbb} hex value, per the shared {@link Colours#isInvalidHex(String)} rule that every
     * user-chosen colour in the app is held to. Submissions with a malformed colour are rejected by the caller ({@link ActionService}) rather than
     * coerced; an <em>absent</em> colour on creation falls back to {@link #DEFAULT_COLOUR}.
     *
     * @param colour the submitted colour
     * @return {@code true} when the colour is an invalid hex value
     */
    static boolean isColourInvalid(final String colour) {
        return Colours.isInvalidHex(colour);
    }
}
