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

/**
 * One reason an archive was refused, located precisely enough to be fixed.
 *
 * <p>
 * A problem always names the member and the line, because an import is all-or-nothing: the user's only route forward is to correct the file, and
 * "something in your archive is invalid" does not tell them where to look.
 *
 * <p>
 * <strong>A reason never quotes note content.</strong> It is worded from the field and the date instead, exactly as the shared text pipeline's own
 * messages are - a rejection banner is not a log file, but a journal entry echoed back into one is still the note leaving the place it belongs.
 *
 * @param file   the archive member the problem is in, or the archive itself when a whole member is missing
 * @param line   the 1-based line, or {@code 0} when the problem is with the member as a whole rather than a row in it
 * @param reason the cause
 */
public record ImportProblem(String file, int line, ImportReason reason) {

}
