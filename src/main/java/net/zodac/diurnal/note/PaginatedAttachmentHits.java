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
 * One page of an attachments search, as {@link NoteAttachmentService} selects it — the attachments themselves and where the page sits in the whole
 * result.
 *
 * <p>
 * The counterpart of {@link PaginatedHits} for files rather than for notes, and split from the row shape each surface renders
 * ({@link PaginatedAttachments}) for the same reason: which attachments match is the service's decision, and how a page of them reads is not.
 *
 * @param items       the page's attachments, most recent day first
 * @param totalCount  the number of attachments matching the search, across all pages
 * @param totalPages  the page count
 * @param currentPage the returned 1-based page (clamped into range)
 */
public record PaginatedAttachmentHits(List<AttachmentHit> items, int totalCount, int totalPages, int currentPage) {

}
