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
 * One run of a search result's preview line: a stretch of the note's own text, flagged as matching the search term or not.
 *
 * <p>
 * A snippet is a <strong>list</strong> of these rather than a single marked-up string precisely so that the highlighting survives Qute's automatic
 * escaping. Emitting {@code <mark>} into a string would mean rendering a note's text raw, which is the one thing the notes feature must never do (see
 * {@code TEXT_INPUT.md}'s "made safe where it is RENDERED" rule) - the template instead loops the parts and decides the markup itself, so every
 * character of the note is still escaped on the way out.
 *
 * @param text        the run of text, exactly as the note holds it (or the ellipsis standing in for the text either side of the window)
 * @param highlighted whether this run is an occurrence of the search term
 */
public record NoteSnippetPart(String text, boolean highlighted) {

}
