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

/**
 * Which page of a list is being shown, and how big the pages are: the result of resolving a requested page number against a total, produced by
 * {@link Pages#window(long, int, int)}.
 *
 * <p>
 * The total row count is deliberately <strong>not</strong> a component. Every caller already holds it (it is what they passed in), and each list's
 * own {@code Paginated*} envelope carries it in its own width - {@code int} for the in-memory lists, {@code long} for the counted queries - so
 * keeping it here would only force one of the two to cast on the way back out.
 *
 * @param totalPages  the number of pages the list spans, {@code 0} when it is empty
 * @param currentPage the 1-based page being shown, already clamped into range ({@code 1} for an empty list)
 * @param pageSize    the page size the window was resolved against
 */
public record PageWindow(int totalPages, int currentPage, int pageSize) {

}
