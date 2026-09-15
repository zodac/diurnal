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
import io.smallrye.config.WithName;
import java.util.List;
import java.util.Optional;

/**
 * Typed view over the {@code notes.attachments.*} settings governing which files may be attached to a day's note — the third sibling mapping under
 * the {@code notes} parent key, beside {@link NotesConfig} (the feature itself) and {@link NotesEncryptionConfig} (its key material).
 *
 * <p>
 * <strong>The size CEILING is not here</strong> — that is {@code app.http.max-attachment-body} ({@code MAX_ATTACHMENT_SIZE}), enforced by
 * {@code http.RequestBodyLimitFilter} on {@code Content-Length} before a body is read, which is where every other body limit in the application
 * lives.
 */
@FunctionalInterface
@ConfigMapping(prefix = "notes.attachments")
public interface NotesAttachmentsConfig {

    /**
     * The file extensions a user may attach, driven by {@code NOTE_ATTACHMENT_EXTENSIONS} — a comma-separated list, matched case-insensitively and
     * with or without a leading dot, so {@code png,.JPG,pdf} and {@code .png,jpg,.pdf} are the same setting.
     *
     * <p>
     * A list holding {@code *} (the default) accepts every extension, including a file that has none at all. Naming extensions instead is a
     * <strong>whitelist</strong>: anything not named is refused, and — since the list is what the file picker offers and what the refusal message
     * repeats — the setting is visible to the user rather than only to the operator.
     *
     * <p>
     * <strong>It bounds what may be STORED, not what has been.</strong> Nothing re-checks an attachment already in the table, so narrowing the list
     * leaves existing files downloadable, previewable and renameable; what they cannot do is be uploaded again.
     *
     * <p>
     * The {@link Optional} wrapper is load-bearing for the same reason as {@link NotesEncryptionConfig#previousKeys()}: the property is always
     * defined (bound to {@code ${NOTE_ATTACHMENT_EXTENSIONS:*}}), so an operator who sets the variable to nothing hands SmallRye an empty string,
     * which its collection converter reads as {@code null} — and a non-optional {@link List} then fails config binding with {@code SRCFG00040}
     * before any application code runs. An empty setting is read as the default, accepting everything.
     *
     * @return the accepted extensions, or empty when every extension is accepted
     */
    @WithName("extensions")
    Optional<List<String>> extensions();
}
