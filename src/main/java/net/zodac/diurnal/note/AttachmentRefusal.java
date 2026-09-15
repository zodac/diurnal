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

/**
 * Why {@link NoteAttachmentService} refused an attachment request, where the reason is a rule of the feature itself rather than of the shared text
 * pipeline (which is carried separately, as a {@code TextOutcome.Failure}).
 *
 * <p>
 * A constant here carries <strong>no display label</strong>, deliberately: a {@code label()} holding English UI text is not a harmless default a
 * template can override, it is the thing that renders in every language until someone notices (see {@code .claude/I18N.md}). The word is resolved at
 * the render site instead — {@code partials/attachment-refusal.html} for the page, {@code AttachmentRefusalExtensions} for the API's hardcoded
 * English — and the enum publishes only its stable {@link #name()}, which is what the API's machine-readable form wants anyway.
 */
public enum AttachmentRefusal {

    /**
     * The file's extension is not one this deployment accepts ({@code NOTE_ATTACHMENT_EXTENSIONS}).
     */
    EXTENSION_NOT_ALLOWED,

    /**
     * The upload carried no bytes at all. An empty file is nothing to attach, and the stored row could not represent one — the
     * {@code note_attachments_byte_size_positive} check makes that unrepresentable at the storage layer.
     */
    EMPTY_FILE,

    /**
     * Another attachment on the same day already has the submitted name. A rename is refused rather than quietly given {@code " (2)"} appended,
     * because the name is the whole of what the user typed (the reject-never-coerce rule) — an UPLOAD, whose name came along with the file rather
     * than being typed, is the case that resolves a collision silently instead.
     */
    DUPLICATE_NAME,

    /**
     * No attachment with the given id belongs to this user — it was already deleted, or it is another account's. The two answer alike on purpose, so
     * an id cannot be probed for existence.
     */
    UNKNOWN_ATTACHMENT,

    /**
     * The attachment's stored name could not be opened, so the request cannot be completed: a rename has no old token to rewrite and a delete has
     * none to remove. This is a deployment fault rather than a user-facing state (the master key is validated at startup), and it is reported rather
     * than worked around because guessing would leave the note's text naming a file that is no longer there.
     */
    UNREADABLE
}
