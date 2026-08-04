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

import java.util.List;

/**
 * The specification of one free-text input: everything that legitimately differs between an action name, a display name, a stat name and a password.
 * Every instance lives in the {@link TextFields} catalogue, and {@link TextValidation} is the only thing that reads one.
 *
 * <p>
 * A minimum length of {@code 0} marks the field as OPTIONAL: a blank submission is accepted and normalises to the empty string, which is how a stat
 * rename is undone (clearing the box restores the catalogue label). Every other field rejects a blank value.
 *
 * <p>
 * A pure data carrier: the normalisation, the wording of a length failure and the constraint list all live in {@link TextFieldExtensions} (PITest
 * cannot hot-swap mutants into a record, so logic held here would silently escape the mutation gate).
 *
 * @param label         the human name of the field, used to word every rejection message
 * @param minLength     the shortest accepted value in code points, or {@code 0} to accept a blank submission
 * @param maxLength     the longest accepted value in code points; must match the width of the column the value is stored in
 * @param normalisation how the submitted value is cleaned before it is measured and stored
 * @param rules         the content checks applied once the value is non-empty and within its length bounds
 */
public record TextField(
    String label,
    int minLength,
    int maxLength,
    Normalisation normalisation,
    List<TextRule> rules
) {

    /**
     * A field holding ordinary human-readable text: {@link Normalisation#CLEANED}, with no extra rules.
     *
     * @param label     the human name of the field
     * @param minLength the shortest accepted value in code points, or {@code 0} to accept a blank submission
     * @param maxLength the longest accepted value in code points
     * @return the field specification
     */
    public static TextField of(final String label, final int minLength, final int maxLength) {
        return new TextField(label, minLength, maxLength, Normalisation.CLEANED, List.of());
    }

    /**
     * A field holding a secret: {@link Normalisation#VERBATIM}, with no extra rules. Only a password should use this - every other input is read back
     * by a human and wants cleaning.
     *
     * @param label     the human name of the field
     * @param minLength the shortest accepted value in code points
     * @param maxLength the longest accepted value in code points
     * @return the field specification
     */
    public static TextField secret(final String label, final int minLength, final int maxLength) {
        return new TextField(label, minLength, maxLength, Normalisation.VERBATIM, List.of());
    }

    /**
     * A copy of this field with the given content rules applied on top of its blank/length checks.
     *
     * @param extraRules the rules to apply
     * @return the field specification, with rules
     */
    public TextField withRules(final TextRule... extraRules) {
        return new TextField(label, minLength, maxLength, normalisation, List.of(extraRules));
    }
}
