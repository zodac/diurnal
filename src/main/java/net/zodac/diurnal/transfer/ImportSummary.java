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
 * What an import will do, or has just done - the incoming totals paired with what the account holds right now.
 *
 * <p>
 * Both halves are carried because the import <strong>replaces</strong>: knowing that a file brings 12 actions is only half of a decision when the
 * account already has 40 that are about to stop existing. The preview shows exactly this, which is what makes the confirmation an informed one
 * rather than a formality.
 *
 * @param actions         the actions the archive brings
 * @param logs            the day counts the archive brings
 * @param notes           the day notes the archive brings
 * @param replacedActions the actions the account holds now, all of which the import removes
 * @param replacedLogs    the day counts the account holds now, all of which the import removes
 * @param replacedNotes   the day notes the account holds now, all of which the import removes
 */
public record ImportSummary(int actions, int logs, int notes, int replacedActions, int replacedLogs, int replacedNotes) {

}
