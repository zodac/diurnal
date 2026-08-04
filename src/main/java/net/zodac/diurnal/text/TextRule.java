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

import java.util.function.Predicate;

/**
 * One content check layered on top of the shared {@link TextValidation} pipeline - the extension point for anything a single field needs that the
 * blank/length checks every field shares do not cover.
 *
 * <p>
 * A rule is applied to the NORMALISED value (see {@link Normalisation}), and only once the value is known to be non-empty and within its length
 * bounds, so a rule never has to re-check either. Adding a rule to one {@link TextField} in {@link TextFields} scopes it to that input; adding it to
 * the shared defaults applies it to every field at once.
 *
 * <p>
 * The requirement is worded RELATIVE to the field that carries it - it is read as a predicate of the field's label, so a single rule reused across
 * several fields words itself correctly for each ("Email must contain an @ symbol.", "Action name must contain an @ symbol."). A rule must therefore
 * never name a field itself.
 *
 * @param id          the stable identifier of the check, mirrored by any client-side evaluation of it
 * @param accepts     whether a given normalised value satisfies the rule
 * @param requirement the human-readable predicate, completing the sentence "{field label} ..."
 */
public record TextRule(
    String id,
    Predicate<String> accepts,
    String requirement
) {
}
