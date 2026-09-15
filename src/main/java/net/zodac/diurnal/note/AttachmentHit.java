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

import java.time.LocalDate;

/**
 * One attachment an account holds, with the day it belongs to — what the attachments search answers with, before either surface decides how to
 * render it.
 *
 * <p>
 * The day is carried alongside the file because an attachment is only addressable through it: the file URL, the note it is embedded in and the
 * dashboard link a result row offers are all per-day, and the day is bound into the seal that opened the name in the first place (see
 * {@link AttachmentContent}).
 *
 * @param date       the day the file is attached to
 * @param attachment the opened attachment
 */
public record AttachmentHit(LocalDate date, Attachment attachment) {

}
