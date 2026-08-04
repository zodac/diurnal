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

import io.quarkus.qute.TemplateExtension;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import net.zodac.diurnal.time.Durations;

/**
 * The derived logic of a {@link TextField}: how a submitted value is normalised, how a length failure is worded, and how the field's bounds are
 * published to the requirements tooltip.
 *
 * <p>
 * Held here rather than on the record because PITest cannot hot-swap mutants into a record class, so logic living there would silently escape the
 * mutation gate.
 */
public final class TextFieldExtensions {

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final String LENGTH_UNIT = "character";

    private TextFieldExtensions() {

    }

    /**
     * Cleans a submitted value into the form that is measured, checked and stored. For a {@link Normalisation#CLEANED} field: control characters
     * become spaces, runs of whitespace collapse to one, the result is stripped, and the composed (NFC) form is returned so two visually identical
     * names compare and sort as equal. A {@link Normalisation#VERBATIM} field is returned untouched.
     *
     * @param field the field specification
     * @param raw   the submitted value
     * @return the value to measure and store
     */
    public static String normalise(final TextField field, final String raw) {
        if (field.normalisation() == Normalisation.VERBATIM) {
            return raw;
        }

        final String spaced = CONTROL_CHARACTERS.matcher(raw).replaceAll(" ");
        final String collapsed = WHITESPACE_RUN.matcher(spaced).replaceAll(" ").strip();
        return Normalizer.normalize(collapsed, Normalizer.Form.NFC);
    }

    /**
     * The number of characters in a value as a reader counts them - code points, not the UTF-16 units {@link String#length()} returns, so a name of
     * emoji is not measured at double its apparent length.
     *
     * @param value the normalised value
     * @return the length in code points
     */
    public static int length(final String value) {
        return value.codePointCount(0, value.length());
    }

    /**
     * The value cut to at most the given number of code points, never splitting a surrogate pair in half (which would leave an unpaired unit in the
     * column). A shorter value is returned unchanged.
     *
     * @param value     the normalised value
     * @param maxLength the longest permitted length in code points
     * @return the value, at most {@code maxLength} code points long
     */
    public static String truncate(final String value, final int maxLength) {
        final int cut = Math.min(length(value), maxLength);
        return value.substring(0, value.offsetByCodePoints(0, cut));
    }

    /**
     * The sentence describing the field's length bounds, used to reject a value that is too short or too long. A field with a real minimum is worded
     * as a range ({@code "Display name must be between 2 and 50 characters."}); one that only has an upper bound states just that
     * ({@code "Password must be at most 128 characters."}).
     *
     * @param field the field specification
     * @return the rejection message
     */
    public static String lengthMessage(final TextField field) {
        final String bounds = field.minLength() > 1
            ? "between " + field.minLength() + " and " + field.maxLength()
            : "at most " + field.maxLength();
        return field.label() + " must be " + bounds + ' ' + Durations.plural(field.maxLength(), LENGTH_UNIT) + '.';
    }

    /**
     * The field's bounds as tooltip rows, driving both the rendered requirements list and its live client-side red/green evaluation. Only the length
     * bounds are published: the {@code layout.html} evaluator understands {@code minLength}/{@code maxLength}, so publishing a
     * {@link TextRule} row would render a requirement that could never turn green. Adding a client-evaluable rule means adding its token there too.
     *
     * @param field the field specification
     * @return the constraints, in display order
     */
    @TemplateExtension
    public static List<TextConstraint> constraints(final TextField field) {
        final List<TextConstraint> constraints = new ArrayList<>();
        if (field.minLength() > 0) {
            constraints.add(new TextConstraint("minLength", field.minLength(), boundLabel("At least", field.minLength())));
        }
        constraints.add(new TextConstraint("maxLength", field.maxLength(), boundLabel("At most", field.maxLength())));
        return constraints;
    }

    private static String boundLabel(final String prefix, final int count) {
        return prefix + ' ' + Durations.count(count, LENGTH_UNIT);
    }
}
