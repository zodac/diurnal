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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The matching and preview rules behind the notes search: whether a note satisfies a search term, and the one-line snippet of its own text shown
 * beside the date on the notes page.
 *
 * <p>
 * <strong>Searching happens on the opened text, in this application, never in the database.</strong> A note is stored sealed (see {@code NOTES.md}),
 * so there is nothing in the {@code notes} table a {@code LIKE} could be run against - the owner's key opens the whole history once and the match is
 * made here. The alternative, a table of per-word blind-index tokens, was rejected: deterministic tokens over natural-language prose are the textbook
 * frequency-analysis target, so a stolen dump would start giving up its contents again - which is the exact thing encrypting the column bought.
 *
 * <p>
 * Matching is a plain case-insensitive substring test, deliberately - the same rule the actions and day-panel filters use, so "search" means one
 * thing across the app. There is no tokenising, stemming or word-boundary logic, so a search for {@code run} finds {@code running} (which a
 * word-based index could not) and finds it inside {@code brunch} too (which it would not). No language is assumed, which matters for a field that
 * accepts every script.
 *
 * <p>
 * The scan runs over the ORIGINAL text, via a {@link Pattern#quote(String) quoted} case-insensitive {@link Pattern}, rather than by lower-casing
 * both sides first. Lower-casing can change a string's LENGTH ({@code U+0130} becomes two characters), which would slide every index afterwards and
 * cut the snippet in the wrong place - a bug that would only appear for some users' text. Quoting is what keeps the term a literal, so a note is
 * searched for the characters typed rather than for a regular expression the user did not mean to write.
 *
 * <p>
 * A pattern is compiled per note rather than once per search, which is a few microseconds against the AES pass that opened the note in the first
 * place - and it keeps the rule a plain two-argument function that both surfaces can call identically.
 *
 * <p>
 * Kept free of persistence and request state so every rule here is deterministically unit-testable.
 */
public final class NoteSearch {

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final String ELLIPSIS = "…";
    private static final int CONTEXT_CHARACTERS = 60;
    private static final int PREVIEW_CHARACTERS = 180;

    private NoteSearch() {

    }

    /**
     * Whether the term keeps every note, so the caller is browsing rather than searching.
     *
     * <p>
     * This is the same rule {@link #matches(String, String)} applies to a blank term, named so a caller can act on it <em>before</em> reading any
     * notes. It is what lets an unfiltered listing be paged in the database: when nothing is being matched, the stored order is the displayed order,
     * so the page can be selected by the query rather than by opening the whole journal and slicing the result. The two must agree - a term this
     * returns {@code true} for is a term {@code matches} accepts every note for - which is why they are stated together here.
     *
     * @param query the search term, already stripped
     * @return {@code true} if every note is kept
     */
    public static boolean matchesEverything(final String query) {
        return query.isBlank();
    }

    /**
     * Whether a note satisfies the search term, case-insensitively. A blank term matches everything, so an empty search box lists the whole journal
     * rather than nothing.
     *
     * @param content the note's readable content
     * @param query   the search term, already stripped
     * @return {@code true} if the note should appear in the results
     */
    public static boolean matches(final String content, final String query) {
        return query.isBlank() || literal(query).matcher(content).find();
    }

    /**
     * Builds the one-line preview shown beside a result's date: a window of the note's own text centred on the first occurrence of the search term,
     * with every occurrence inside that window flagged for highlighting.
     *
     * <p>
     * The note's line breaks are flattened to spaces first, because this renders as a single row in a table - a journal entry's paragraphs would
     * otherwise make each result an arbitrary number of lines tall. With a blank term (or one that only matched outside the previewed window) the
     * result is simply the head of the note, so the page doubles as a plain browse view of everything written.
     *
     * @param content the note's readable content
     * @param query   the search term, already stripped
     * @return the snippet's runs of text, in reading order
     */
    public static List<NoteSnippetPart> snippet(final String content, final String query) {
        final String flattened = WHITESPACE_RUN.matcher(content).replaceAll(" ").strip();
        if (query.isBlank()) {
            return preview(flattened);
        }

        final Pattern term = literal(query);
        final Matcher first = term.matcher(flattened);
        if (!first.find()) {
            return preview(flattened);
        }

        final int from = cut(flattened, first.start() - CONTEXT_CHARACTERS);
        final int to = cut(flattened, first.end() + CONTEXT_CHARACTERS);
        // A REGION rather than a hand-checked bound: the matcher then refuses to report a match that runs past the window's end, so a term
        // straddling the cut is excluded by construction rather than by arithmetic that could drift out of step with the substring below.
        return highlighted(flattened, term.matcher(flattened).region(from, to), from, to);
    }

    private static List<NoteSnippetPart> preview(final String flattened) {
        if (flattened.length() <= PREVIEW_CHARACTERS) {
            return List.of(new NoteSnippetPart(flattened, false));
        }
        return List.of(
            new NoteSnippetPart(flattened.substring(0, cut(flattened, PREVIEW_CHARACTERS)), false),
            new NoteSnippetPart(ELLIPSIS, false));
    }

    private static List<NoteSnippetPart> highlighted(final String text, final Matcher matcher, final int from, final int to) {
        final List<NoteSnippetPart> parts = new ArrayList<>();
        if (from > 0) {
            parts.add(new NoteSnippetPart(ELLIPSIS, false));
        }

        int cursor = from;
        while (matcher.find()) {
            if (matcher.start() > cursor) {
                parts.add(new NoteSnippetPart(text.substring(cursor, matcher.start()), false));
            }
            // The matched run is taken from the NOTE, not from the query, so the result shows the user's own casing.
            parts.add(new NoteSnippetPart(matcher.group(), true));
            cursor = matcher.end();
        }

        if (cursor < to) {
            parts.add(new NoteSnippetPart(text.substring(cursor, to), false));
        }
        if (to < text.length()) {
            parts.add(new NoteSnippetPart(ELLIPSIS, false));
        }
        return List.copyOf(parts);
    }

    // The search term as a case-insensitive LITERAL, so a note is searched for the characters typed rather than for a regular expression the user
    // never meant to write - a stray '(' would otherwise be a 500 rather than a search that finds nothing.
    private static Pattern literal(final String query) {
        return Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    // Clamps a window edge into the text, and nudges it off the second half of a surrogate pair so a cut never splits an astral character (an emoji,
    // which notes accept) into two unpaired halves that render as replacement characters.
    private static int cut(final String text, final int index) {
        final int clamped = Math.clamp(index, 0, text.length());
        return clamped < text.length() && Character.isLowSurrogate(text.charAt(clamped)) ? (clamped - 1) : clamped;
    }
}
