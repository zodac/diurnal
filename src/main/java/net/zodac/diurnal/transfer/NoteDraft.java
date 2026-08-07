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
 * One validated day note from an import archive, ready to be sealed and written.
 *
 * <p>
 * A note may be dated in the FUTURE, unlike a log - the same rule the note box itself follows, since planning a day in advance is a legitimate thing
 * to write down where claiming to have already performed an action then is not.
 *
 * <p>
 * The content is plaintext and is treated as the private thing it is: it is never logged, and never quoted in a rejection message.
 *
 * @param date    the day the note belongs to
 * @param content the normalised note content, never empty
 */
public record NoteDraft(LocalDate date, String content) {

}
