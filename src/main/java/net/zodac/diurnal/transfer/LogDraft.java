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
 * One validated day count from an import archive, ready to be written.
 *
 * <p>
 * The action is carried by NAME, exactly as the file names it: the actions it could refer to are being created by the same import, so no id exists
 * to point at until the write itself is under way.
 *
 * @param date       the day the count was recorded against, never in the future
 * @param actionName the normalised name of the action it counts, guaranteed to be one of the archive's own actions
 * @param count      the count, within {@code 1..ActionLog.MAX_DAILY_COUNT}
 */
public record LogDraft(LocalDate date, String actionName, int count) {

}
