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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TextValidationTest {

    // One astral-plane code point: two UTF-16 units, but one character to a reader.
    private static final String EMOJI = Character.toString(0x1F600);
    // BEL: a control character that is NOT whitespace, so only the control-character pass can remove it.
    private static final String BELL = Character.toString(0x0007);
    private static final String COMBINING_ACUTE = Character.toString(0x0301);
    private static final String E_ACUTE = Character.toString(0x00E9);

    private static Stream<TextField> catalogue() {
        return TextFields.all().stream();
    }

    private static Stream<TextField> requiredFields() {
        return TextFields.all().stream().filter(field -> field.minLength() > 0);
    }

    // A value of exactly `length` code points that satisfies every content rule the field carries.
    private static String valueOfLength(final TextField field, final int length, final String unit) {
        return field.rules().contains(TextRules.EMAIL_SHAPE)
            ? unit.repeat(length - 1) + '@'
            : unit.repeat(length);
    }

    private static String valueOfLength(final TextField field, final int length) {
        return valueOfLength(field, length, "a");
    }

    // ── the shared pipeline, asserted across every field in the catalogue ──────

    @ParameterizedTest
    @MethodSource("requiredFields")
    void check_null_isBlank(final TextField field) {
        assertThat(TextValidation.check(field, null))
            .as("a null submission must be rejected as blank")
            .isInstanceOf(TextOutcome.Blank.class);
    }

    @ParameterizedTest
    @MethodSource("requiredFields")
    void check_empty_isBlank(final TextField field) {
        assertThat(TextValidation.check(field, ""))
            .as("an empty submission must be rejected as blank")
            .isInstanceOf(TextOutcome.Blank.class);
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void check_valueAtMaxLength_isAccepted(final TextField field) {
        assertThat(TextValidation.check(field, valueOfLength(field, field.maxLength())))
            .as("a value of exactly the maximum length must be accepted")
            .isInstanceOf(TextOutcome.Valid.class);
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void check_valueOverMaxLength_isTooLong(final TextField field) {
        assertThat(TextValidation.check(field, valueOfLength(field, field.maxLength() + 1)))
            .as("a value one character over the maximum must be rejected")
            .isInstanceOf(TextOutcome.TooLong.class);
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void check_maxLengthInEmoji_isAccepted(final TextField field) {
        // Each emoji is two UTF-16 units, so a String.length() check would reject this at half its apparent length.
        assertThat(TextValidation.check(field, valueOfLength(field, field.maxLength(), EMOJI)))
            .as("length must be measured in code points, not UTF-16 units")
            .isInstanceOf(TextOutcome.Valid.class);
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void check_acceptedValue_isReturnedForStorage(final TextField field) {
        final String value = valueOfLength(field, field.maxLength());

        assertThat(TextValidation.check(field, value))
            .as("the accepted value must be returned for the caller to store")
            .isEqualTo(new TextOutcome.Valid(value));
    }

    // ── length bounds ─────────────────────────────────────────────────────────

    @Test
    void check_belowMinimumLength_isTooShort() {
        assertThat(TextValidation.check(TextFields.DISPLAY_NAME, "a"))
            .as("a value under the minimum length must be rejected")
            .isInstanceOf(TextOutcome.TooShort.class);
    }

    @Test
    void check_atMinimumLength_isAccepted() {
        assertThat(TextValidation.check(TextFields.DISPLAY_NAME, "ab"))
            .as("a value of exactly the minimum length must be accepted")
            .isInstanceOf(TextOutcome.Valid.class);
    }

    // ── optional fields ───────────────────────────────────────────────────────

    @Test
    void check_optionalField_blankIsAcceptedAsEmpty() {
        assertThat(TextValidation.check(TextFields.STAT_NAME, "   "))
            .as("a blank submission to an optional field is the accepted 'no value' reset")
            .isEqualTo(new TextOutcome.Valid(""));
    }

    @Test
    void check_optionalField_nullIsAcceptedAsEmpty() {
        assertThat(TextValidation.check(TextFields.STAT_NAME, null))
            .as("a null submission to an optional field is the accepted 'no value' reset")
            .isEqualTo(new TextOutcome.Valid(""));
    }

    @Test
    void check_requiredField_whitespaceOnlyIsBlank() {
        assertThat(TextValidation.check(TextFields.ACTION_NAME, " \t "))
            .as("a whitespace-only submission normalises to empty, so it must be rejected as blank")
            .isInstanceOf(TextOutcome.Blank.class);
    }

    // ── normalisation ─────────────────────────────────────────────────────────

    @Test
    void check_cleanedField_stripsSurroundingWhitespace() {
        assertThat(TextValidation.check(TextFields.ACTION_NAME, "  Running  "))
            .as("unexpected value")
            .isEqualTo(new TextOutcome.Valid("Running"));
    }

    @Test
    void check_cleanedField_collapsesWhitespaceRuns() {
        assertThat(TextValidation.check(TextFields.ACTION_NAME, "Morning     run"))
            .as("unexpected value")
            .isEqualTo(new TextOutcome.Valid("Morning run"));
    }

    @Test
    void check_cleanedField_replacesControlCharacters() {
        assertThat(TextValidation.check(TextFields.DISPLAY_NAME, "Ada" + BELL + "Lovelace"))
            .as("a control character must never reach the column")
            .isEqualTo(new TextOutcome.Valid("Ada Lovelace"));
    }

    @Test
    void check_cleanedField_composesToNfc() {
        // "e" + a combining acute must be stored as the single composed character, so two visually identical names compare as equal.
        assertThat(TextValidation.check(TextFields.ACTION_NAME, "Cafe" + COMBINING_ACUTE))
            .as("unexpected value")
            .isEqualTo(new TextOutcome.Valid("Caf" + E_ACUTE));
    }

    @Test
    void check_cleanedField_measuredAfterNormalising() {
        // A legal name padded past its bound is still legal: the length is taken from the cleaned value.
        final String padded = "  " + "a".repeat(TextFields.ACTION_NAME_MAX_LENGTH) + "  ";

        assertThat(TextValidation.check(TextFields.ACTION_NAME, padded))
            .as("length must be measured after normalisation")
            .isInstanceOf(TextOutcome.Valid.class);
    }

    @Test
    void check_secretField_isNeverNormalised() {
        // Cleaning a password would change the secret, and stop an already-registered one from ever matching again.
        final String password = "  hunter2  spaced  ";

        assertThat(TextValidation.check(TextFields.PASSWORD, password))
            .as("a password must be used exactly as submitted")
            .isEqualTo(new TextOutcome.Valid(password));
    }

    @Test
    void check_secretField_whitespaceOnlyIsAccepted() {
        assertThat(TextValidation.check(TextFields.PASSWORD, "   "))
            .as("whitespace is part of a secret, so a whitespace-only password is a real one")
            .isEqualTo(new TextOutcome.Valid("   "));
    }

    @Test
    void check_secretField_emptyIsStillBlank() {
        assertThat(TextValidation.check(TextFields.PASSWORD, ""))
            .as("an empty password is no password at all")
            .isInstanceOf(TextOutcome.Blank.class);
    }

    // ── content rules ─────────────────────────────────────────────────────────

    @Test
    void check_ruleFailure_reportsTheFailedRule() {
        assertThat(TextValidation.check(TextFields.EMAIL, "not-an-email"))
            .as("unexpected value")
            .isEqualTo(new TextOutcome.RuleFailed(TextFields.EMAIL, TextRules.EMAIL_SHAPE));
    }

    @Test
    void check_ruleSatisfied_isAccepted() {
        assertThat(TextValidation.check(TextFields.EMAIL, "user@diurnal.example.com"))
            .as("unexpected value")
            .isInstanceOf(TextOutcome.Valid.class);
    }

    @Test
    void check_rulesRunAfterLengthChecks() {
        // An over-long value that ALSO fails a rule is reported as too long: the length check comes first.
        final String tooLong = "a".repeat(TextFields.EMAIL_MAX_LENGTH + 1);

        assertThat(TextValidation.check(TextFields.EMAIL, tooLong))
            .as("length is checked before the content rules")
            .isInstanceOf(TextOutcome.TooLong.class);
    }

    @Test
    void check_multipleRules_reportsTheFirstFailure() {
        final TextRule alwaysFails = new TextRule("alwaysFails", value -> false, "Never satisfied.");
        final TextField field = TextField.of("Test", 1, 50).withRules(TextRules.EMAIL_SHAPE, alwaysFails);

        assertThat(TextValidation.check(field, "no-at-symbol"))
            .as("the first failing rule must be the one reported")
            .isEqualTo(new TextOutcome.RuleFailed(field, TextRules.EMAIL_SHAPE));
    }

    @Test
    void check_failure_carriesTheField() {
        assertThat(TextValidation.check(TextFields.ACTION_NAME, ""))
            .as("a rejection must carry the field it was checked against, so it can be worded")
            .isEqualTo(new TextOutcome.Blank(TextFields.ACTION_NAME));
    }

    // ── coerce ────────────────────────────────────────────────────────────────

    @Test
    void coerce_valueWithinBounds_isTheNormalisedValue() {
        assertThat(TextValidation.coerce(TextFields.DISPLAY_NAME, "  Ada   Lovelace  "))
            .as("unexpected value")
            .contains("Ada Lovelace");
    }

    @Test
    void coerce_overLongValue_isTruncated() {
        final String tooLong = "a".repeat(TextFields.DISPLAY_NAME_MAX_LENGTH + 1);

        assertThat(TextValidation.coerce(TextFields.DISPLAY_NAME, tooLong))
            .as("a coerced value is trimmed to the bound rather than rejected")
            .contains("a".repeat(TextFields.DISPLAY_NAME_MAX_LENGTH));
    }

    @Test
    void coerce_null_isEmpty() {
        assertThat(TextValidation.coerce(TextFields.DISPLAY_NAME, null))
            .as("unexpected value")
            .isEmpty();
    }

    @Test
    void coerce_blank_isEmpty() {
        assertThat(TextValidation.coerce(TextFields.DISPLAY_NAME, "   "))
            .as("unexpected value")
            .isEmpty();
    }

    @Test
    void coerce_underMinimumLength_isEmpty() {
        assertThat(TextValidation.coerce(TextFields.DISPLAY_NAME, "a"))
            .as("trimming cannot lengthen a value, so a too-short one stays unusable")
            .isEmpty();
    }

    @Test
    void coerce_optionalFieldGivenBlank_isEmpty() {
        // An optional field accepts a blank value, but there is nothing to coerce it TO - the caller wants a usable value or nothing.
        assertThat(TextValidation.coerce(TextFields.STAT_NAME, "  "))
            .as("unexpected value")
            .isEmpty();
    }

    @Test
    void coerce_ruleFailure_isEmpty() {
        // A content rule cannot be satisfied by trimming, so a failing value is never coerced into one that passes.
        assertThat(TextValidation.coerce(TextFields.EMAIL, "no-at-symbol"))
            .as("unexpected value")
            .isEmpty();
    }

    // ── the catalogue itself ──────────────────────────────────────────────────

    @Test
    void catalogue_holdsEveryField() {
        final List<TextField> expected = List.of(
            TextFields.ACTION_NAME,
            TextFields.DISPLAY_NAME,
            TextFields.STAT_NAME,
            TextFields.EMAIL,
            TextFields.PASSWORD);

        assertThat(TextFields.all())
            .as("a field missing from the catalogue escapes every test that sweeps it")
            .containsExactlyElementsOf(expected);
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void catalogue_everyFieldHasSaneBounds(final TextField field) {
        assertThat(field.maxLength())
            .as("a field's maximum must exceed its minimum")
            .isGreaterThan(field.minLength());
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void catalogue_onlyThePasswordIsVerbatim(final TextField field) {
        final Normalisation expected = field.equals(TextFields.PASSWORD) ? Normalisation.VERBATIM : Normalisation.CLEANED;

        assertThat(field.normalisation())
            .as("only a secret may skip normalisation")
            .isEqualTo(expected);
    }
}
