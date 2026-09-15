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

import java.util.List;
import java.util.Optional;
import net.zodac.diurnal.note.NotesAttachmentsConfig;

/**
 * Reusable {@link NotesAttachmentsConfig} stub built from its record component.
 *
 * @param configuredExtensions the accepted extensions, or empty when the setting is unset
 */
public record StubNotesAttachmentsConfig(Optional<List<String>> configuredExtensions) implements NotesAttachmentsConfig {

    /**
     * A stub carrying the shipped default — accept everything — for a test that needs the config but is not about which extensions are accepted.
     *
     * @return the stub
     */
    public static StubNotesAttachmentsConfig withDefaults() {
        return new StubNotesAttachmentsConfig(Optional.of(List.of("*")));
    }

    /**
     * A stub accepting only the named extensions.
     *
     * @param extensions the extensions to accept, with or without their dots
     * @return the stub
     */
    public static StubNotesAttachmentsConfig accepting(final String... extensions) {
        return new StubNotesAttachmentsConfig(Optional.of(List.of(extensions)));
    }

    /**
     * A stub for the setting being unset altogether, which is how an operator who clears the variable arrives.
     *
     * @return the stub
     */
    public static StubNotesAttachmentsConfig unset() {
        return new StubNotesAttachmentsConfig(Optional.empty());
    }

    @Override
    public Optional<List<String>> extensions() {
        return configuredExtensions;
    }
}
