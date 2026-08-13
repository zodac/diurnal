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
 * The result of validating one submitted value, as a sealed type so a caller's {@code switch} over it is exhaustive at compile time.
 *
 * <p>
 * Callers map this onto their own domain result ({@code ActionResult}, {@code ProfileResult}, ...) rather than returning it: the shared pipeline
 * decides WHAT is wrong, and each surface decides how to say so. A caller that needs no per-cause distinction can collapse every rejection into the
 * single {@link Failure} branch and take its {@link TextOutcomeExtensions#message(Failure)}.
 */
public sealed interface TextOutcome permits TextOutcome.Valid, TextOutcome.Failure {

    /**
     * The value is accepted.
     *
     * @param value the NORMALISED value - what the caller must store, never the raw submission; empty for an accepted blank submission to an
     *              optional field
     */
    record Valid(String value) implements TextOutcome {

    }

    /**
     * The value is rejected. Every cause carries the field it was checked against, so the rejection can be worded without the caller passing the
     * field back in.
     */
    sealed interface Failure extends TextOutcome permits Blank, TooShort, TooLong, RuleFailed {

        /**
         * The field the value was checked against.
         *
         * <p>
         * Never invoked through this interface - every caller switches to a concrete variant first ({@code TextOutcomeExtensions.message}) and calls
         * that record's own component - so an IDE reports it unused. It is a <strong>contract</strong>, not a call site: declaring it here is what
         * forces each permitted variant to carry the field, which is the guarantee the class Javadoc above makes and the reason a rejection can be
         * worded without the caller passing the field back in. A new variant that forgot it would not compile.
         *
         * @return the field specification
         */
        @SuppressWarnings("unused") // a contract every variant must carry, not a call site; callers switch to the concrete record first
        TextField field();
    }

    /**
     * Nothing was submitted, for a field that requires a value.
     *
     * @param field the field specification
     */
    record Blank(TextField field) implements Failure {

    }

    /**
     * The value is shorter than the field's minimum.
     *
     * @param field the field specification
     */
    record TooShort(TextField field) implements Failure {

    }

    /**
     * The value is longer than the field's maximum. Always a rejection, never a truncation - a value is never stored as something other than what
     * was typed.
     *
     * @param field the field specification
     */
    record TooLong(TextField field) implements Failure {

    }

    /**
     * The value failed one of the field's content rules.
     *
     * @param field the field specification
     * @param rule  the rule that rejected the value
     */
    record RuleFailed(TextField field, TextRule rule) implements Failure {

    }
}
