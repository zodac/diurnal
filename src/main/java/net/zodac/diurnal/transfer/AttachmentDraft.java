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

package net.zodac.diurnal.transfer;

import java.time.LocalDate;

/**
 * One validated {@code attachments.csv} row, carrying the bytes the archive held for it.
 *
 * <p>
 * The bytes travel with the draft rather than being looked up again at write time, so an {@link ImportPlan} is what its own Javadoc says it is:
 * something that needs no further checking and no further reading. They are already in memory either way — the whole archive was decompressed to
 * validate it — so this costs nothing beyond a second reference.
 *
 * <p>
 * Holding an array, it carries the identity {@code equals}/{@code hashCode} a record gives an array component. Nothing compares two drafts; they are
 * built, validated as a list and written.
 *
 * @param date     the day the attachment belongs to
 * @param name     the display name, which the day's note embeds it by
 * @param fileName the name the file was uploaded under, which no rename rewrites
 * @param file     the attachment's bytes, as the archive held them
 */
public record AttachmentDraft(LocalDate date, String name, String fileName, byte[] file) {

}
