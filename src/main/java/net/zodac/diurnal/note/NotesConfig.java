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

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Typed view over the {@code notes.*} settings governing the day-note feature itself, as distinct from the {@code notes.encryption.*} key material in
 * {@link NotesEncryptionConfig} (two sibling mappings under one parent key, exactly as {@code password} and {@code password.hash.argon2} are).
 */
@FunctionalInterface
@ConfigMapping(prefix = "notes")
public interface NotesConfig {

    /**
     * The longest note a user may save, in code points, driven by {@code NOTE_MAX_LENGTH}.
     *
     * <p>
     * <strong>There is no database limit to match this to.</strong> A note is stored sealed in an unbounded {@code bytea} (the plaintext
     * {@code notes.content} column was dropped in {@code V28}), so this bound exists only in
     * {@link net.zodac.diurnal.text.TextValidation} and changing it needs no migration. What it really governs is the size of the responses and the
     * work the notes feature does: the dashboard warms a three-month window of note CONTENT in one request, the public range feed returns 31 notes
     * at a time, and every search opens the account's whole journal to match on the plaintext - so each of those scales directly with this value.
     * The permitted range is enforced at startup by {@code AppLifecycle}; see
     * {@link net.zodac.diurnal.text.TextFields#NOTE_MAX_LENGTH_CEILING} for what sets its upper end.
     *
     * @return the longest accepted note in code points, defaulting to {@value net.zodac.diurnal.text.TextFields#NOTE_MAX_LENGTH}
     */
    @WithName("max-length")
    @WithDefault("10000")
    int maxLength();
}
