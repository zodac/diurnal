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

/**
 * The "did you mean" offered beside a search that matched nothing: the closest word the journal actually holds, and the link that searches for it.
 *
 * <p>
 * The two travel together because they must not get out of step - the word is what the sentence reads, the link is that same word encoded into a
 * {@code ?q=}, and a page showing one without the other would either be unclickable or search for something it does not name.
 *
 * <p>
 * <strong>The word is the user's own writing</strong>, lifted from a note ({@link NoteSearch#suggest}), so it is rendered back only to its author,
 * escaped like any other note text, and never logged.
 *
 * <p>
 * Following it is an ordinary full-page search, not an HTMX swap: the term has to end up in the address bar and in the search box, and a fragment
 * swap would leave the box holding the term that found nothing - which the pagination links then keep sending (see {@code data-search-source}).
 *
 * @param word      the closest word the journal holds, in the casing it was written in
 * @param url       the notes-page link that searches for that word
 * @param noteCount how many notes following that link returns, so the offer says what it is worth
 */
public record NoteSuggestion(String word, String url, int noteCount) {
}
