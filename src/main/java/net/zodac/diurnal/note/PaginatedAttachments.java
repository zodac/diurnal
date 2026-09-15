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
 * One page of the notes page's attachments table, in the shape {@code partials/pagination.html} expects from every list view in the app.
 *
 * <p>
 * There is deliberately no "did you mean" here, unlike {@link PaginatedNotes}: a suggestion is drawn from the words of the journal itself, and a
 * filename is not writing - a near-miss on one is a mistyped name rather than a word the account nearly holds.
 *
 * @param items       the page's rows, most recent day first
 * @param totalCount  the number of attachments matching the search, across all pages
 * @param totalPages  the page count
 * @param currentPage the returned 1-based page (clamped into range, as every web list view does)
 */
public record PaginatedAttachments(List<AttachmentRow> items, int totalCount, int totalPages, int currentPage) {

}
