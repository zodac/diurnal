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

package net.zodac.diurnal.persistence;

/**
 * The vendor-specific native SQL backing {@code NoteAttachment}'s bulk write path - the {@code unnest} insert a data import uses to replace a whole
 * account's attachments in one round trip - gathered behind one interface so that supporting a second database is a matter of adding an
 * implementation rather than editing the entity. The {@link LogStatements}/{@link NoteStatements} counterpart for attachments; see either for the
 * rules both follow.
 *
 * <p>
 * There is exactly one statement here, unlike its two siblings: an attachment's single-row write ({@code NoteAttachment.store}) never contends with
 * a concurrent one the way a log increment or a note edit can - a data import always calls {@code NoteAttachment.deleteByUser} first - so there is
 * no per-row upsert needing a vendor-neutral JPQL counterpart, only the bulk arm the import's write path needs.
 *
 * <p>
 * <strong>Every implementation must use the same {@code :named} placeholders</strong>, declared once as typed {@link QueryParameter} tokens in
 * {@code NoteAttachmentQueries} and bound by {@code NoteAttachment}. The method below records the exact set its statement must declare, and
 * {@code NoteAttachmentQueriesTest} pins the shipped implementation to it.
 *
 * <p>
 * An implementation is selected by {@code quarkus.datasource.db-kind}, so the datasource and the statements can never disagree.
 */
@FunctionalInterface
public interface NoteAttachmentStatements {

    /**
     * Writes many attachments for one user in a single statement - the bulk arm of {@code NoteAttachment.store}, for the data import, which
     * replaces a whole account's attachments at once. The six lists are parallel: index {@code i} of each describes one attachment. Passing empty
     * lists must be a clean no-op.
     *
     * <p>
     * Round-trip-bound at the scale an attachment-heavy account reaches: measured at 4.93s as 20,000 individual inserts against 1.12s as this one
     * statement.
     *
     * <p>
     * Declares {@code :userId}, {@code :idArray}, {@code :dateArray}, {@code :displayNameArray}, {@code :fileNameArray}, {@code :contentArray},
     * {@code :byteSizeArray} and {@code :now}.
     *
     * @return the statement text
     */
    String insertAll();
}
