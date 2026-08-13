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

package net.zodac.diurnal.page;

import java.util.List;

/**
 * The one place a page number is resolved against a total, and the one place a fetched list is sliced into the page it produced.
 *
 * <p>
 * Every list view in the app pages the same way - fetch all, filter, slice (see the pagination section of {@code CLAUDE.md}) - so the arithmetic
 * behind it (how many pages a total spans, and which page a requested number actually lands on) was previously written out at each list. Repeating it
 * is what lets two lists disagree about an edge that has no obvious right answer until it is settled once: an empty list still has a page 1, and a
 * page number past the end is <strong>clamped</strong> to the last real page rather than answered as empty. Settling it here also means the paging
 * arithmetic is unit-testable on its own, without a list or a container to hold it.
 *
 * <p>
 * Clamping is the <strong>web</strong> surface's policy. The public API rejects an out-of-range page instead (surface policy, marked as such at each
 * API resource), and asks this only for {@link PageWindow#totalPages()} so it knows what "out of range" means.
 */
public final class Pages {

    private Pages() {

    }

    /**
     * Resolves a requested page number against a total row count.
     *
     * @param totalCount the number of rows the list holds in total
     * @param pageNum    the requested 1-based page, clamped into range
     * @param pageSize   the page size to resolve against
     * @return the resolved window
     */
    public static PageWindow window(final long totalCount, final int pageNum, final int pageSize) {
        final int totalPages = (int) ((totalCount + pageSize - 1L) / pageSize);
        final int currentPage = Math.clamp(pageNum, 1, totalPages == 0 ? 1 : totalPages);
        return new PageWindow(totalPages, currentPage, pageSize);
    }

    /**
     * Slices an already-fetched (and already-ordered) list into the rows the given window shows.
     *
     * @param all    every row the list holds, in display order
     * @param window the window resolved by {@link #window(long, int, int)} over {@code all.size()}
     * @param <T>    the row type
     * @return the rows on the window's page, which is empty when the list is
     */
    public static <T> List<T> slice(final List<T> all, final PageWindow window) {
        return all.stream()
            .skip((long) (window.currentPage() - 1) * window.pageSize())
            .limit(window.pageSize())
            .toList();
    }
}
