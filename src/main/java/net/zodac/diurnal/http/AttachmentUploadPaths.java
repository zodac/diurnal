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

package net.zodac.diurnal.http;

import java.util.regex.Pattern;

/**
 * The two note-attachment UPLOAD endpoints, named here so {@link RequestBodyLimitFilter} can hold them to a body ceiling of their own.
 *
 * <p>
 * They are the one ordinary capability whose request body is a file the USER chose rather than a form this application designed - why they need a
 * bigger cap, and why everything else can keep a small one. The set is deliberately narrow: the {@code POST} that carries bytes, on each surface,
 * and nothing beside it. A rename or a delete on the same resource carries a few dozen bytes of JSON and stays under the ordinary cap, and the
 * listing endpoints carry no body at all.
 *
 * <p>
 * This is the {@link ImportPaths} pattern for the same reason: a path that drifts out of the set silently regains the small cap, and the symptom is
 * a {@code 413} on a file the deployment says it accepts.
 */
final class AttachmentUploadPaths {

    // POST /api/v1/notes/{date}/attachments - the public upload. The date segment matches "anything but a slash" rather than a date: this runs
    // before the resource parses it, so a malformed date is the resource's 400 to give, not a reason to apply the wrong limit here.
    private static final Pattern API_UPLOAD = Pattern.compile("api/v1/notes/[^/]+/attachments");

    // POST /internal/note-attachments/{date} - the note box's upload. Two segments exactly, so the rename and delete endpoints beneath it
    // (/{date}/{id}/rename, /{date}/{id}/delete) are not matched.
    private static final Pattern INTERNAL_UPLOAD = Pattern.compile("internal/note-attachments/[^/]+");

    private static final String POST_METHOD = "POST";

    private AttachmentUploadPaths() {

    }

    /**
     * Whether a request is one of the note-attachment uploads.
     *
     * <p>
     * The METHOD is part of the question, not decoration: {@code GET /internal/note-attachments/list} has the same shape as the upload path, and
     * only the {@code POST} carries a file.
     *
     * @param method the request method
     * @param path   the request path, with or without a leading slash
     * @return {@code true} when the request is an attachment upload
     */
    static boolean isAttachmentUpload(final String method, final String path) {
        if (!POST_METHOD.equalsIgnoreCase(method)) {
            return false;
        }

        final String normalised = path.startsWith("/") ? path.substring(1) : path;
        return API_UPLOAD.matcher(normalised).matches() || INTERNAL_UPLOAD.matcher(normalised).matches();
    }
}
