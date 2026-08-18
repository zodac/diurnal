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

package net.zodac.diurnal.text;

/**
 * One requirement of a {@link TextField}, in the form the requirements tooltip renders and the client-side live check evaluates.
 *
 * @param type  the client-side check token ({@code minLength} or {@code maxLength}); mirrored by the evaluator in {@code layout.html} AND by the
 *              tooltip's own translated-text switch ({@code partials/password-constraints.html}), since the two possible values ({@code "At least
 *              N characters"}/{@code "At most N characters"}) follow directly from which bound this is
 * @param value the numeric bound the check compares the value's length against
 */
public record TextConstraint(
    String type,
    int value
) {
}
