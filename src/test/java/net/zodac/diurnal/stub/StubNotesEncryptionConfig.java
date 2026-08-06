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
import net.zodac.diurnal.config.NotesEncryptionConfig;

/**
 * Reusable {@link NotesEncryptionConfig} stub built from its record components.
 *
 * @param configuredKey the current master key, base64-encoded
 * @param previous the retired keys a rotation should try, or empty for no rotation
 */
public record StubNotesEncryptionConfig(String configuredKey, List<String> previous) implements NotesEncryptionConfig {

    /**
     * A stub carrying only a current key, with no rotation configured — the ordinary case.
     *
     * @param configuredKey the current master key, base64-encoded
     * @return the stub
     */
    public static StubNotesEncryptionConfig of(final String configuredKey) {
        return new StubNotesEncryptionConfig(configuredKey, List.of());
    }

    @Override
    public Optional<String> key() {
        return Optional.of(configuredKey);
    }

    @Override
    public Optional<List<String>> previousKeys() {
        return Optional.of(previous);
    }
}
