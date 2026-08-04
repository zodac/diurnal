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

    @Test
    void of_buildsCleanedFieldWithoutRules() {
        final TextField field = TextField.of("Nickname", 2, 30);

        assertThat(field)
            .as("unexpected value")
            .isEqualTo(new TextField("Nickname", 2, 30, Normalisation.CLEANED, List.of()));
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

        assertThat(field)
            .as("unexpected value")
            .isEqualTo(new TextField("Nickname", 2, 30, Normalisation.CLEANED, List.of(TextRules.EMAIL_SHAPE)));
    }

    @Test
    void withRules_givenNone_clearsThem() {
        final TextField field = TextFields.EMAIL.withRules();

        assertThat(field.rules())
            .as("unexpected value")
            .isEmpty();
    }
}
