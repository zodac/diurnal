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

package net.zodac.diurnal.stats;

/**
 * One stat the user has chosen to show on the Stats page: the {@link StatField} to render, paired with the caption to render it under. The
 * caption is the field's own {@link StatField#label()} unless the user has renamed the stat, in which case it is their name for it.
 *
 * <p>
 * The pairing exists because a rename is a per-user value and a field is a global constant, so the enum alone cannot carry the caption to the page.
 * Only the caption varies: which statistic is computed, and how its figure is derived, remains entirely the field's.
 *
 * @param field the catalogue field to render
 * @param label the caption to render it under (the user's rename, or the field's own label)
 */
public record DisplayStat(StatField field, String label) {

}
