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

import java.util.UUID;

/**
 * One of a day's attachments, opened: its id, the display name the note's {@code [[name]]} token says, the name the file was uploaded under, and the
 * size of the file behind it.
 *
 * <p>
 * The readable counterpart of {@link SealedAttachment}, and what every surface renders from — the note box's chip list, the public API's listing, and
 * the two write endpoints' echo. The bytes are deliberately not here: they are fetched one attachment at a time by the preview or the download, so
 * carrying them would make listing a day cost every file on it.
 *
 * <p>
 * <strong>The two names differ only after a rename</strong>, which rewrites {@code name} and leaves {@code fileName} alone — so for most attachments
 * they read identically, and that is the point: the one that was half-remembered is whichever one the reader is looking for. An attachment stored
 * before the file name was kept reports its display name as both, since that is all there ever was of it.
 *
 * <p>
 * What the hover card may preview is derived from a name rather than stored, by {@link AttachmentNames#previewFor(String)} — a record holds data,
 * and the derivation is a rule with a security edge to it (see that method). It is derived from {@code fileName}: what the bytes ARE cannot be
 * changed by relabelling them, and a rename may legitimately leave the display name with no extension at all.
 *
 * @param id       the attachment's id, which the download and the two write endpoints address it by
 * @param name     the display name, which the note's own text embeds
 * @param fileName the name the file was uploaded under, extension and all, which no rename rewrites
 * @param byteSize the size of the stored file in bytes
 */
public record Attachment(UUID id, String name, String fileName, int byteSize) {

}
