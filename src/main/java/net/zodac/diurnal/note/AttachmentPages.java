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

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import net.zodac.diurnal.http.AppPaths;
import net.zodac.diurnal.time.DayLabels;

/**
 * Turns the attachments a search matched into the page the attachments table renders — each row's spelled-out day, both of its names with the
 * matching run marked, rounded size and file URL.
 *
 * <p>
 * The {@link NotePages} of the second table on the notes page, and split out of the resources for the same reasons: the row shape stays unit-testable
 * without a container, and the full-page render cannot drift from the HTMX list fragment, which must produce exactly the same rows.
 *
 * <p>
 * Which attachments match, and which page of them this is, was decided by {@link NoteAttachmentService#searchPage(net.zodac.diurnal.user.User,
 * String, int, int)}; nothing here narrows or re-orders anything.
 */
public final class AttachmentPages {

    private static final int BYTES_PER_KILOBYTE = 1024;

    private AttachmentPages() {

    }

    /**
     * Renders an already-selected page of matching attachments as its rows.
     *
     * @param hits     the page's attachments and its place in the whole result, most recent day first
     * @param query    the search term, used to mark the matching run in each of the row's two names
     * @param locale   the viewing user's locale, for each row's spelled-out day
     * @param appPaths the single builder of every application URL, for each row's file link
     * @return the requested page
     */
    public static PaginatedAttachments of(final PaginatedAttachmentHits hits, final String query, final Locale locale, final AppPaths appPaths) {
        final List<AttachmentRow> items = hits.items()
            .stream()
            .map(hit -> row(hit, query, locale, appPaths))
            .toList();

        return new PaginatedAttachments(items, hits.totalCount(), hits.totalPages(), hits.currentPage());
    }

    /**
     * A file's size in whole kilobytes, rounded UP and never to zero.
     *
     * <p>
     * A file that exists is at least one kilobyte's worth of "there is something here", and {@code 0 KB} reads as an empty file - which the upload
     * would have refused ({@link AttachmentRefusal#EMPTY_FILE}). One unit rather than a scale, for the reason
     * {@code AppMessages#attachmentSizeKb} gives: the figure is bounded by the deployment's request-body limit, so it rarely leaves kilobytes.
     *
     * <p>
     * Keep-in-sync pair with {@code note.js}'s own {@code kilobytes()}, which rounds the note box's hover card the same way - the same file must not
     * read as two different sizes on one page.
     *
     * @param byteSize the file's size in bytes
     * @return the size in whole kilobytes, at least {@code 1}
     */
    public static int kilobytes(final int byteSize) {
        return Math.max(1, (byteSize + BYTES_PER_KILOBYTE - 1) / BYTES_PER_KILOBYTE);
    }

    private static AttachmentRow row(final AttachmentHit hit, final String query, final Locale locale, final AppPaths appPaths) {
        final LocalDate date = hit.date();
        final Attachment attachment = hit.attachment();
        // The size goes in as TEXT, because the message that renders it takes text (see AttachmentRow): a bundle method's parameter type is what
        // Qute passes the value into, and an int handed to a String parameter is a ClassCastException at render time rather than a build failure.
        // BOTH names are marked: a term matches either, so marking only one would leave a row whose reason for being here is invisible.
        return new AttachmentRow(date.toString(), DayLabels.spelledOut(date, locale), NoteSearch.marked(attachment.name(), query),
            NoteSearch.marked(attachment.fileName(), query), Integer.toString(kilobytes(attachment.byteSize())),
            appPaths.internalNoteAttachmentFile(date, attachment.id()));
    }
}
