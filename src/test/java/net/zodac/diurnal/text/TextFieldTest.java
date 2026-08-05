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
import org.junit.jupiter.api.Test;

class TextFieldTest {

    private static final List<TextRule> SHARED_RULES = List.of(TextRules.NO_INVISIBLE_CHARACTERS, TextRules.NO_STACKED_MARKS);

    @Test
    void of_buildsCleanedFieldCarryingTheSharedRules() {
        final TextField field = TextField.of("Nickname", 2, 30);

        assertThat(field)
            .as("every readable field must carry the shared content rules, so none can be added that skips them")
            .isEqualTo(new TextField("Nickname", 2, 30, Normalisation.CLEANED, SHARED_RULES));
    }

    @Test
    void secret_buildsVerbatimFieldWithoutRules() {
        final TextField field = TextField.secret("Passphrase", 1, 64);

        assertThat(field)
            .as("a secret must never be normalised")
            .isEqualTo(new TextField("Passphrase", 1, 64, Normalisation.VERBATIM, List.of()));
    }

    @Test
    void withRules_keepsEveryOtherPartOfTheSpec() {
        final TextField field = TextField.of("Nickname", 2, 30).withRules(TextRules.EMAIL_SHAPE);

        final List<TextRule> expected = List.of(TextRules.NO_INVISIBLE_CHARACTERS, TextRules.NO_STACKED_MARKS, TextRules.EMAIL_SHAPE);
        assertThat(field)
            .as("unexpected value")
            .isEqualTo(new TextField("Nickname", 2, 30, Normalisation.CLEANED, expected));
    }

    @Test
    void withRules_addsToTheRulesAlreadyPresent() {
        // A field-specific rule must never be able to displace a shared one.
        final TextRule extra = new TextRule("extra", value -> true, "is always fine.");

        assertThat(TextField.of("Nickname", 2, 30).withRules(extra).rules())
            .as("the shared rules must survive a field adding its own")
            .containsExactlyElementsOf(List.of(TextRules.NO_INVISIBLE_CHARACTERS, TextRules.NO_STACKED_MARKS, extra));
    }

    @Test
    void withRules_givenNone_keepsTheRulesAlreadyPresent() {
        assertThat(TextFields.EMAIL.withRules().rules())
            .as("unexpected value")
            .containsExactlyElementsOf(List.of(TextRules.NO_INVISIBLE_CHARACTERS, TextRules.NO_STACKED_MARKS, TextRules.EMAIL_SHAPE));
    }
}
