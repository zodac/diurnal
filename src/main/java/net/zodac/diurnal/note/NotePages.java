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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import net.zodac.diurnal.time.DayLabels;

/**
 * Turns the notes a search matched into the page the notes list renders — each row's spelled-out day and highlighted snippet.
 *
 * <p>
 * This is presentation, not a business rule: which notes match is {@link NoteService}'s decision, and this only chooses how one page of them reads.
 * It is split out of the resources so the row shape is unit-testable without a container, and so the full-page render and
 * the HTMX list fragment cannot drift into producing different pages for the same inputs.
 *
 * <p>
 * The page has already been chosen by the time it arrives here - by the database when nothing is being searched for, by filtering the opened journal
 * when something is (see {@link NoteService#journalPage(net.zodac.diurnal.user.User, String, int, int)}). An out-of-range page was
 * <strong>clamped</strong> rather than rejected, the web-surface policy every other list view follows (the public API rejects instead; see
 * {@code NotesApiResource}).
 */
public final class NotePages {

    private NotePages() {

    }

    /**
     * Renders an already-selected page of matching notes as its rows.
     *
     * @param hits   the page's notes and its place in the whole result, most recent first
     * @param query  the search term, used to highlight each row's snippet
     * @param locale the viewing user's locale, for each row's spelled-out day
     * @return the requested page
     */
    public static PaginatedNotes of(final PaginatedHits hits, final String query, final Locale locale) {
        final List<NoteRow> items = hits.items()
            .stream()
            .map(hit -> new NoteRow(hit.date().toString(), DayLabels.spelledOut(hit.date(), locale), NoteSearch.snippet(hit.content(), query)))
            .toList();

        return new PaginatedNotes(items, Math.toIntExact(hits.totalCount()), hits.totalPages(), hits.currentPage());
    }

    /**
     * Builds the pre-encoded query suffix the shared pagination footer appends to its page links, so paging through results keeps the search term.
     *
     * @param searchTerm the search term, possibly blank
     * @return {@code "&q=…"} URL-encoded, or an empty string when nothing is being searched for
     */
    public static String extraQuery(final String searchTerm) {
        return searchTerm.isBlank() ? "" : ("&q=" + URLEncoder.encode(searchTerm, StandardCharsets.UTF_8));
    }
}
