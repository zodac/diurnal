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

package net.zodac.diurnal.web;

import io.quarkus.qute.TemplateExtension;

/**
 * The CLDR plural category for Arabic, which distinguishes six grammatical forms rather than the English/Spanish
 * two-way singular/plural split {@link AppMessages}' other pluralised entries otherwise use — see the
 * {@code msg_ar-SA.properties} notes in {@code .claude/I18N.md}'s Phase 5 for why this exists and which entries use
 * it. A {@code {msg:...}} value cannot compute this itself (Qute's expression language has no modulo/range
 * operators), and a Java-side caller of {@link AppMessages} can never be locale-aware (see that interface's own
 * class Javadoc) — so this is resolved as a {@link TemplateExtension}, callable directly on a count argument inside
 * an {@code @Message} value (e.g. {@code {count.arabicPluralCategory}}), the same "derive in Java, branch in the
 * template" split every other per-locale grammar decision in this codebase uses.
 *
 * <p>
 * The rule itself is the official CLDR "ar" plural rule set (unchanged since CLDR's own definition, not a project
 * invention): {@code zero} for exactly 0, {@code one} for exactly 1, {@code two} for exactly 2, {@code few} for a
 * count whose last two digits fall in {@code 3..10}, {@code many} for {@code 11..99}, and {@code other} for
 * everything else (e.g. round hundreds, or any count ending {@code 00}/{@code 01}/{@code 02}).
 */
// The int/long overloads below share a name and parameter count by necessity, not oversight: Qute resolves
// `{count.arabicPluralCategory}` by the STATIC type of `count` in the @Message parameter list it came from, so
// each numeric type an entry might declare needs its own exactly-named overload.
@SuppressWarnings("OverloadedMethodsWithSameNumberOfParameters")
public final class ArabicPlural {

    private ArabicPlural() {

    }

    /**
     * Resolves the CLDR "ar" plural category for a count.
     *
     * @param count the count, as an {@code int}-typed {@code @Message} parameter
     * @return one of {@code "zero"}, {@code "one"}, {@code "two"}, {@code "few"}, {@code "many"}, {@code "other"}
     */
    @TemplateExtension
    public static String arabicPluralCategory(final int count) {
        return arabicPluralCategory((long) count);
    }

    /**
     * Resolves the CLDR "ar" plural category for a count.
     *
     * @param count the count, as a {@code long}-typed {@code @Message} parameter
     * @return one of {@code "zero"}, {@code "one"}, {@code "two"}, {@code "few"}, {@code "many"}, {@code "other"}
     */
    @TemplateExtension
    @SuppressWarnings("MagicNumber") // the CLDR "ar" rule's own boundaries, already cited by the class Javadoc
    public static String arabicPluralCategory(final long count) {
        if (count == 0) {
            return "zero";
        }
        if (count == 1) {
            return "one";
        }
        if (count == 2) { // NOPMD: AvoidLiteralsInIfCondition - the CLDR ar rule genuinely singles out exactly two (dual)
            return "two";
        }

        final long lastTwoDigits = count % 100;
        if (lastTwoDigits >= 3 && lastTwoDigits <= 10) {
            return "few";
        }
        if (lastTwoDigits >= 11) { // NOPMD: AvoidLiteralsInIfCondition - the CLDR ar rule's own "many" lower bound
            return "many";
        }
        return "other";
    }
}
