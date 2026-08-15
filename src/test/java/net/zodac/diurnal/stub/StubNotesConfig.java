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

package net.zodac.diurnal.stub;

import net.zodac.diurnal.config.NotesConfig;
import net.zodac.diurnal.text.TextFields;

/**
 * Reusable {@link NotesConfig} stub built from its record component.
 *
 * @param configuredMaxLength the longest accepted note, in code points
 */
public record StubNotesConfig(int configuredMaxLength) implements NotesConfig {

    /**
     * A stub carrying the shipped default bound — the ordinary case, for a test that needs a {@link NotesConfig} but is not about the bound.
     *
     * @return the stub
     */
    public static StubNotesConfig withDefaults() {
        return new StubNotesConfig(TextFields.NOTE_MAX_LENGTH);
    }

    @Override
    public int maxLength() {
        return configuredMaxLength;
    }
}
