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
import org.jspecify.annotations.Nullable;

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
 * <p>
 * The {@code suggestion} is the "did you mean" word, and is present only when a real term matched <strong>nothing</strong> - it is the answer to an
 * empty result, never an addition to a non-empty one. It is a word lifted from the user's own journal ({@link NoteSearch#suggest}) paired with
 * what searching for it finds, so it is as private as the notes it came from: it may be rendered back to its author and must never be logged.
 *
 * <p>
 * {@code totalCount} is how many notes MATCHED; {@code selectionCount} is how many were considered before the term was applied - the whole journal
 * for the notes page, the date range for the API's ranged feed. They are equal whenever nothing is being searched for. The pair exists so a caller
 * can tell "this account has written nothing" from "this term matched nothing" <strong>without a second count query</strong>: the search path has
 * already selected every note it could match, so its size is an answer already in hand.
 *
 * @param items          the page's notes, in the order the reading surface asked for
 * @param totalCount     the number of notes matching the search, across all pages
 * @param selectionCount the number of notes the page was selected from, before any search term was applied
 * @param totalPages     the page count
 * @param currentPage    the returned 1-based page (clamped into range by {@link net.zodac.diurnal.page.Pages})
 * @param suggestion     the closest word the journal holds to a term that matched nothing, or {@code null} when there is nothing to suggest
 */
public record PaginatedHits(List<NoteHit> items, long totalCount, long selectionCount, int totalPages, int currentPage,
    @Nullable SuggestedTerm suggestion) {

}
