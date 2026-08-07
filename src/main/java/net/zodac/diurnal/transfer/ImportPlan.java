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

import java.util.List;

/**
 * A fully validated archive, ready to be written as it stands.
 *
 * <p>
 * Nothing in a plan needs checking again: every value has been through the shared validators once, and every log's action is known to be one of the
 * plan's own actions. That is what lets the preview and the commit run the identical parse and differ only in whether they write - the preview is
 * not an approximation of the import, it is the same work with the last step left off.
 *
 * @param actions the actions to create
 * @param logs    the day counts to create
 * @param notes   the day notes to create
 */
public record ImportPlan(List<ActionDraft> actions, List<LogDraft> logs, List<NoteDraft> notes) {

}
