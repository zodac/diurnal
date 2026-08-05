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

import java.util.ArrayList;
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

    private static final List<TextRule> SHARED_RULES = List.of(TextRules.NO_INVISIBLE_CHARACTERS, TextRules.NO_STACKED_MARKS);

    /**
     * A field holding ordinary human-readable text: {@link Normalisation#CLEANED}, carrying the rules every readable field shares
     * ({@link TextRules#NO_INVISIBLE_CHARACTERS}, {@link TextRules#NO_STACKED_MARKS}). A rule added here applies to every such field at once, which
     * is the point: a cross-cutting content policy cannot be forgotten by the next field that is added.
     *
     * @param label     the human name of the field
     * @param minLength the shortest accepted value in code points, or {@code 0} to accept a blank submission
     * @param maxLength the longest accepted value in code points
     * @return the field specification
     */
    public static TextField of(final String label, final int minLength, final int maxLength) {
        return new TextField(label, minLength, maxLength, Normalisation.CLEANED, SHARED_RULES);
    }

    /**
     * A field holding a secret: {@link Normalisation#VERBATIM}, with no rules at all - not even the shared ones. Only a password should use this:
     * every other input is read back by a human and wants cleaning, whereas a secret is never rendered, never compared against another user's value
     * and is stored only as a hash, so the reasons the shared rules exist do not apply to it. Constraining which characters a password may hold would
     * also shrink the keyspace, and would lock out anyone whose existing password holds one.
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
     * A copy of this field with the given content rules added to the ones it already carries, on top of its blank/length checks. The rules already
     * present are kept, so a field-specific rule can never displace a shared one.
     *
     * @param extraRules the rules to add
     * @return the field specification, with rules
     */
    public TextField withRules(final TextRule... extraRules) {
        final List<TextRule> combined = new ArrayList<>(rules);
        combined.addAll(List.of(extraRules));
        return new TextField(label, minLength, maxLength, normalisation, List.copyOf(combined));
    }
}
