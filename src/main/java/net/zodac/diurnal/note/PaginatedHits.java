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

import java.util.List;

/**
 * One page of the notes a search kept, paired with the total that matched across every page.
 *
 * <p>
 * This is what {@link NoteService}'s paged reads answer, and it carries the total precisely because the page alone cannot be counted to find it: when
 * a listing is paged in the database, the rows the caller receives are the only ones that were ever read, so the total has to come from a separate
 * {@code COUNT} rather than from the size of the result.
 *
 * <p>
 * It stops one step short of presentation. Each surface turns these into its own shape - the notes page into highlighted {@link NoteRow}s through
 * {@link NotePages}, the public API into its DTOs - so the page arithmetic is settled once, here, and rendered twice.
 *
 * @param items       the page's notes, in the order the reading surface asked for
 * @param totalCount  the number of notes matching the search, across all pages
 * @param totalPages  the page count
 * @param currentPage the returned 1-based page (clamped into range by {@link net.zodac.diurnal.page.Pages})
 */
public record PaginatedHits(List<NoteHit> items, long totalCount, int totalPages, int currentPage) {

}
