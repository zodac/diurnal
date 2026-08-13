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

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The single validator every free-text input in the app passes through - action name, display name, stat name, email and password alike - so a rule
 * is written once and cannot drift between the web UI and the public API, or between one field and the next.
 *
 * <p>
 * The pipeline is fixed and identical for every field: normalise (per the field's {@link Normalisation}) - reject an empty value unless the field is
 * optional - check the length in code points - apply the field's {@link TextRule}s. Only the field specification varies, so a new cross-cutting rule
 * is one entry in {@link TextRules} rather than an edit to every caller.
 *
 * <p>
 * Callers translate the returned {@link TextOutcome} into their own sealed domain result, and store {@link TextOutcome.Valid#value()} - the
 * normalised value - never the raw submission. What to DO about a rejection stays a surface decision: the public API answers 4xx, a web form may
 * coerce.
 */
public final class TextValidation {

    private TextValidation() {

    }

    /**
     * The submitted value forced into the field's bounds, for the callers that have no user to report a rejection to - today, provisioning a display
     * name from an OIDC claim. The value is normalised and truncated to the field's maximum; if what remains is still unusable (empty, or under the
     * minimum) the result is empty and the caller must try another candidate.
     *
     * <p>
     * Every OTHER caller must use {@link #check(TextField, String)} and reject: a value a user typed is never silently stored as something else.
     * Content rules are deliberately not coerced - a value that fails one cannot be repaired by trimming it.
     *
     * @param field the field specification, from the {@link TextFields} catalogue
     * @param raw   the submitted value ({@code null} is treated as an empty submission)
     * @return the coerced value, or empty when it cannot be made to fit
     */
    public static Optional<String> coerce(final TextField field, final @Nullable String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        // Normalised ONCE here, then checked as-is: normalisation is idempotent, but running it twice over one value is the kind of duplicated pass
        // this pipeline exists to remove.
        final String truncated = TextFieldExtensions.truncate(TextFieldExtensions.normalise(field, raw), field.maxLength());
        return checkNormalised(field, truncated) instanceof TextOutcome.Valid(final String value) && !value.isEmpty()
            ? Optional.of(value)
            : Optional.empty();
    }

    /**
     * Validates a submitted value against a field specification.
     *
     * @param field the field specification, from the {@link TextFields} catalogue
     * @param raw   the submitted value ({@code null} is treated as an empty submission)
     * @return the outcome
     */
    public static TextOutcome check(final TextField field, final @Nullable String raw) {
        return checkNormalised(field, raw == null ? "" : TextFieldExtensions.normalise(field, raw));
    }

    private static TextOutcome checkNormalised(final TextField field, final String value) {
        // A CLEANED value that held only whitespace has already been stripped to empty, so this single check covers a blank submission to any field.
        if (value.isEmpty()) {
            return field.minLength() == 0 ? new TextOutcome.Valid(value) : new TextOutcome.Blank(field);
        }

        final int length = TextFieldExtensions.length(value);
        if (length < field.minLength()) {
            return new TextOutcome.TooShort(field);
        }
        if (length > field.maxLength()) {
            return new TextOutcome.TooLong(field);
        }

        for (final TextRule rule : field.rules()) {
            if (!rule.accepts().test(value)) {
                return new TextOutcome.RuleFailed(field, rule);
            }
        }

        return new TextOutcome.Valid(value);
    }
}
