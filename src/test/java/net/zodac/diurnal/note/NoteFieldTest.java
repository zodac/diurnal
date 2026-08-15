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

package net.zodac.diurnal.note;

import static org.assertj.core.api.Assertions.assertThat;

import net.zodac.diurnal.stub.StubNotesConfig;
import net.zodac.diurnal.text.Normalisation;
import net.zodac.diurnal.text.TextFields;
import net.zodac.diurnal.text.TextOutcome;
import net.zodac.diurnal.text.TextValidation;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NoteField}: that the day-note specification the application validates against is built from the configured
 * {@code NOTE_MAX_LENGTH}, and that nothing else about the field moves with it.
 */
class NoteFieldTest {

    @Test
    void field_takesItsMaximumFromTheConfiguration() {
        assertThat(new NoteField(new StubNotesConfig(250)).field().maxLength())
            .as("the bound in force is the deployment's, not the catalogue default")
            .isEqualTo(250);
    }

    @Test
    void field_withNoConfiguredValue_isTheCatalogueDefault() {
        assertThat(new NoteField(StubNotesConfig.withDefaults()).field())
            .as("an unset NOTE_MAX_LENGTH must leave the note exactly as the catalogue publishes it")
            .isEqualTo(TextFields.NOTE);
    }

    @Test
    void field_changingTheBound_changesNothingElseAboutTheField() {
        final var field = new NoteField(new StubNotesConfig(250)).field();

        assertThat(field.normalisation())
            .as("a note stays the app's one MULTILINE field however its bound is set - line breaks are content")
            .isEqualTo(Normalisation.MULTILINE);
        assertThat(field.minLength())
            .as("a note stays optional, so clearing the box and saving still deletes it")
            .isZero();
        assertThat(field.rules())
            .as("the shared content rules must not be lost when the field is rebuilt")
            .isEqualTo(TextFields.NOTE.rules());
    }

    @Test
    void field_isTheBoundActuallyEnforced() {
        final var field = new NoteField(new StubNotesConfig(10)).field();

        assertThat(TextValidation.check(field, "x".repeat(10)))
            .as("a note exactly at the configured bound is accepted")
            .isEqualTo(new TextOutcome.Valid("x".repeat(10)));
        assertThat(TextValidation.check(field, "x".repeat(11)))
            .as("one code point past it is rejected, worded from the configured bound")
            .isEqualTo(new TextOutcome.TooLong(field));
    }
}
