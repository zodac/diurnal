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

class TextOutcomeExtensionsTest {

    private static Stream<TextField> requiredFields() {
        return TextFields.all().stream().filter(field -> field.minLength() > 0);
    }

    @Test
    void message_blank_namesTheField() {
        assertThat(TextOutcomeExtensions.message(new TextOutcome.Blank(TextFields.DISPLAY_NAME)))
            .as("unexpected value")
            .isEqualTo("Display name cannot be empty.");
    }

    @Test
    void message_tooShort_statesTheBounds() {
        assertThat(TextOutcomeExtensions.message(new TextOutcome.TooShort(TextFields.DISPLAY_NAME)))
            .as("unexpected value")
            .isEqualTo("Display name must be between 2 and 50 characters.");
    }

    @Test
    void message_tooLong_statesTheBounds() {
        assertThat(TextOutcomeExtensions.message(new TextOutcome.TooLong(TextFields.PASSWORD)))
            .as("unexpected value")
            .isEqualTo("Password must be at most 128 characters.");
    }

    @Test
    void message_ruleFailure_isTheRuleRequirement() {
        assertThat(TextOutcomeExtensions.message(new TextOutcome.RuleFailed(TextFields.EMAIL, TextRules.EMAIL_SHAPE)))
            .as("unexpected value")
            .isEqualTo("Email must contain an @ symbol.");
    }

    @Test
    void message_ruleFailure_isWordedForWhicheverFieldCarriesTheRule() {
        // A rule states its requirement relative to the field, so one shared rule words itself correctly everywhere it is used.
        final TextField field = TextField.of("Action name", 1, 100).withRules(TextRules.EMAIL_SHAPE);

        assertThat(TextOutcomeExtensions.message(new TextOutcome.RuleFailed(field, TextRules.EMAIL_SHAPE)))
            .as("unexpected value")
            .isEqualTo("Action name must contain an @ symbol.");
    }

    @ParameterizedTest
    @MethodSource("requiredFields")
    void message_neverQuotesTheSubmittedValue(final TextField field) {
        // Every way a value can be rejected, for every field that can reject one: none of the wordings may echo what was submitted.
        final String submitted = "hunter2";
        final List<TextOutcome.Failure> failures = List.of(
            new TextOutcome.Blank(field),
            new TextOutcome.TooShort(field),
            new TextOutcome.TooLong(field));

        assertThat(failures)
            .extracting(TextOutcomeExtensions::message)
            .as("a message must never echo the submitted value - one of these fields is a password")
            .noneMatch(message -> message.contains(submitted));
    }
}
